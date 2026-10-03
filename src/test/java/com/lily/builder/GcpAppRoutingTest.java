package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** GCP 로 배포한 앱의 관리 호출(롤백, 내리기, 삭제, 주소)은 AWS 가 아니라 GCP lily-cicd·로드밸런서로 간다 */
class GcpAppRoutingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient.Builder awsHttp = RestClient.builder().baseUrl("http://aws-cicd");
    private final MockRestServiceServer awsCicd = MockRestServiceServer.bindTo(awsHttp).build();
    private final RestClient.Builder gcpHttp = RestClient.builder().baseUrl("http://gcp-cicd");
    private final MockRestServiceServer gcpCicd = MockRestServiceServer.bindTo(gcpHttp).build();
    private final AgentDeployService agents = mock(AgentDeployService.class);
    private final List<String> dns = new ArrayList<>();
    private final AppAddress addresses = new AppAddress(
            new PlatformProperties.Cloudflare("token", "acc", "zone", "lilycloud.kr"), "alb.example.net",
            (method, path, body) -> {
                dns.add(method + " " + path + (body == null ? "" : " " + body));
                return path.contains("/dns_records?name=") ? JSON.createArrayNode() : json("{}");
            });

    private BuildController controller(CloudRouting clouds) {
        when(agents.rollback(anyString())).thenReturn(Optional.empty());
        when(agents.schema(anyString())).thenReturn(Optional.empty());
        when(agents.completeSchema(anyString())).thenReturn(Optional.empty());
        return new BuildController(mock(BuildService.class), mock(ClusterApps.class), clouds, agents, addresses,
                EdgeWorker.disabled(), null);
    }

    private CloudRouting connected() {
        return new CloudRouting(agents, new CicdClient(awsHttp.build()), new CicdClient(gcpHttp.build()),
                "gcp.lilycloud.kr");
    }

    @Test
    void GCP_앱의_롤백_내리기_올리기_릴리스_스키마_삭제는_GCP_cicd_로_가고_AWS_는_부르지_않는다() {
        when(agents.cloudProvider("blog")).thenReturn("GCP");
        BuildController controller = controller(connected());
        for (String[] call : new String[][]{
                {"POST", "/api/deployments/blog/rollback"}, {"POST", "/api/apps/blog/stop"},
                {"POST", "/api/apps/blog/start"}, {"GET", "/api/deployments/blog"},
                {"GET", "/api/deployments/blog/schema"}, {"POST", "/api/deployments/blog/schema/complete"},
                {"DELETE", "/api/apps/blog"}}) {
            gcpCicd.expect(requestTo(startsWith("http://gcp-cicd" + call[1])))
                    .andExpect(method(HttpMethod.valueOf(call[0])))
                    .andRespond(withSuccess("{\"status\":\"OK\"}", MediaType.APPLICATION_JSON));
        }

        controller.rollback("blog", null);
        controller.stop("blog");
        controller.startApp("blog");
        controller.release("blog");
        controller.schema("blog");
        controller.completeSchema("blog");
        ResponseEntity<String> removed = controller.remove("blog", true);

        assertThat(removed.getStatusCode().value()).isEqualTo(200);
        gcpCicd.verify();
        awsCicd.verify();
    }

    @Test
    void 기록이_없거나_AWS_인_앱은_예전처럼_AWS_cicd_로_간다() {
        when(agents.cloudProvider("blog")).thenReturn("AWS");
        awsCicd.expect(requestTo("http://aws-cicd/api/deployments/blog/rollback"))
                .andRespond(withSuccess("{\"status\":\"ROLLED_BACK\"}", MediaType.APPLICATION_JSON));

        assertThat(controller(connected()).rollback("blog", null).getBody()).contains("ROLLED_BACK");
        awsCicd.verify();
        gcpCicd.verify();
    }

    @Test
    void GCP_가_연결되지_않은_GCP_앱은_503_으로_거절하고_AWS_로_보내지_않는다() {
        when(agents.cloudProvider("blog")).thenReturn("GCP");
        BuildController controller = controller(new CloudRouting(agents, new CicdClient(awsHttp.build()), null, ""));

        assertThatThrownBy(() -> controller.rollback("blog", null)).isInstanceOf(CloudRouting.Unconfigured.class);
        ResponseEntity<String> response = controller.unconfigured(new CloudRouting.Unconfigured(CloudClients.MISSING));
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).contains("GCP_CICD_URL");
        awsCicd.verify();
    }

    @Test
    void GCP_앱의_주소를_클라우드로_되돌리면_GCP_로드밸런서_CNAME_을_쓴다() {
        when(agents.cloudProvider("blog")).thenReturn("GCP");

        ResponseEntity<?> response = controller(connected())
                .pointAddress("blog", new BuildController.AddressRequest("cloud"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(dns).anyMatch(call -> call.startsWith("POST ") && call.contains("\"content\":\"gcp.lilycloud.kr\""));
        assertThat(dns).noneMatch(call -> call.contains("alb.example.net"));
    }

    @Test
    void 내_PC_GCP_앱을_지우면_GCP_클러스터의_대기_배포를_지운다() {
        when(agents.cloudProvider("blog")).thenReturn("GCP");
        when(agents.remove("blog", true))
                .thenReturn(Optional.of(new AgentDeployService.AgentRemoval("key", "removed", "")));
        OnPremAppRemoval removal = new OnPremAppRemoval(agents, mock(AgentCloudflare.class), AppAddress.disabled(),
                EdgeWorker.disabled(), connected());
        gcpCicd.expect(requestTo("http://gcp-cicd/api/apps/blog?database=true"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"status\":\"REMOVED\"}", MediaType.APPLICATION_JSON));

        Optional<Map<String, Object>> result = removal.remove("blog", true);

        assertThat(result).isPresent();
        assertThat(result.get()).containsEntry("cluster", "removed");
        gcpCicd.verify();
        awsCicd.verify();
    }

    @Test
    void GCP_가_연결되지_않으면_내_PC_앱을_지우기_전에_거절한다() {
        when(agents.cloudProvider("blog")).thenReturn("GCP");
        OnPremAppRemoval removal = new OnPremAppRemoval(agents, mock(AgentCloudflare.class), AppAddress.disabled(),
                EdgeWorker.disabled(), new CloudRouting(agents, new CicdClient(awsHttp.build()), null, ""));

        assertThatThrownBy(() -> removal.remove("blog", true)).isInstanceOf(CloudRouting.Unconfigured.class);
        verify(agents, never()).remove(anyString(), anyBoolean());
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
