package com.lily.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 멀티클라우드 앱의 관리 호출은 두 클라우드에 순서대로 간다. 스키마는 GCP 만 */
class MultiCloudRoutingTest {

    private final RestClient.Builder awsHttp = RestClient.builder().baseUrl("http://aws-cicd");
    private final MockRestServiceServer aws = MockRestServiceServer.bindTo(awsHttp).build();
    private final RestClient.Builder gcpHttp = RestClient.builder().baseUrl("http://gcp-cicd");
    private final MockRestServiceServer gcp = MockRestServiceServer.bindTo(gcpHttp).build();
    private final AgentDeployService agents = mock(AgentDeployService.class);
    private final EdgeWorker edge = mock(EdgeWorker.class);
    private BuildController controller;

    @BeforeEach
    void setUp() {
        when(agents.cloudProvider("blog")).thenReturn("GCP");
        when(agents.multiCloud("blog")).thenReturn(true);
        when(agents.rollback(anyString())).thenReturn(Optional.empty());
        CloudRouting clouds = new CloudRouting(agents, new CicdClient(awsHttp.build()), new CicdClient(gcpHttp.build()),
                "gcp.lilycloud.kr");
        controller = new BuildController(mock(BuildService.class), mock(ClusterApps.class), clouds, agents,
                AppAddress.disabled(), edge, null);
    }

    @Test
    void 롤백은_AWS_앱부터_되돌리고_GCP를_스키마까지_되돌린다() {
        aws.expect(requestTo("http://aws-cicd/api/deployments/blog/rollback")).andExpect(content().json("{\"appOnly\":false}"))
                .andRespond(withSuccess("{\"status\":\"ROLLED_BACK\"}", MediaType.APPLICATION_JSON));
        gcp.expect(requestTo("http://gcp-cicd/api/deployments/blog/rollback")).andExpect(content().json("{\"appOnly\":false}"))
                .andRespond(withSuccess("{\"status\":\"ROLLED_BACK\",\"schemaVersion\":\"01\"}", MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = controller.rollback("blog", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"schemaVersion\":\"01\"");
        aws.verify();
        gcp.verify();
    }

    @Test
    void AWS_롤백이_거절되면_GCP는_건드리지_않는다() {
        aws.expect(requestTo("http://aws-cicd/api/deployments/blog/rollback"))
                .andRespond(withStatus(HttpStatus.CONFLICT).body("{\"status\":\"REJECTED\",\"message\":\"이전 슬롯이 없다\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        gcp.expect(never(), requestTo("http://gcp-cicd/api/deployments/blog/rollback"));

        ResponseEntity<String> response = controller.rollback("blog", null);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        gcp.verify();
    }

    @Test
    void 삭제는_AWS_앱을_DB_없이_지우고_GCP에서_DB까지_지운다() {
        when(edge.enabled()).thenReturn(true);
        aws.expect(requestTo("http://aws-cicd/api/apps/blog?database=false")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"deleted\":[]}", MediaType.APPLICATION_JSON));
        gcp.expect(requestTo("http://gcp-cicd/api/apps/blog?database=true")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"deleted\":[],\"database\":\"deleted\"}", MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = controller.remove("blog", true);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(edge).detach("blog");
        aws.verify();
        gcp.verify();
    }

    @Test
    void 내리기는_AWS부터_올리기는_GCP부터_두_클라우드에_보낸다() {
        aws.expect(requestTo("http://aws-cicd/api/apps/blog/stop")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        gcp.expect(requestTo("http://gcp-cicd/api/apps/blog/stop")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        gcp.expect(requestTo("http://gcp-cicd/api/apps/blog/start")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        aws.expect(requestTo("http://aws-cicd/api/apps/blog/start")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(controller.stop("blog").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.startApp("blog").getStatusCode().value()).isEqualTo(200);
        aws.verify();
        gcp.verify();
    }

    @Test
    void 릴리스는_GCP_릴리스에_AWS_릴리스를_붙여_준다() throws Exception {
        gcp.expect(requestTo("http://gcp-cicd/api/deployments/blog"))
                .andRespond(withSuccess("{\"activeSlot\":\"stable\",\"slots\":[]}", MediaType.APPLICATION_JSON));
        aws.expect(requestTo("http://aws-cicd/api/deployments/blog"))
                .andRespond(withSuccess("{\"activeSlot\":\"stable\",\"slots\":[{\"slot\":\"stable\"}]}", MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = controller.release("blog");

        var body = new ObjectMapper().readTree(response.getBody());
        assertThat(body.path("multiCloud").asBoolean()).isTrue();
        assertThat(body.path("aws").path("slots").size()).isEqualTo(1);
    }

    @Test
    void 비율을_바꾸면_엣지에_넣고_클라우드별_Pod_수와_함께_돌려준다() {
        when(edge.splitOf("blog")).thenReturn(30);
        gcp.expect(requestTo("http://gcp-cicd/api/apps/blog"))
                .andRespond(withSuccess("{\"replicas\":2,\"readyReplicas\":2}", MediaType.APPLICATION_JSON));
        aws.expect(requestTo("http://aws-cicd/api/apps/blog"))
                .andRespond(withSuccess("{\"replicas\":2,\"readyReplicas\":1}", MediaType.APPLICATION_JSON));

        ResponseEntity<?> response = controller.traffic("blog", new BuildController.TrafficRequest(30));

        verify(edge).split("blog", 30);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("gcpPercent", 30);
        assertThat(body.get("aws")).isEqualTo(Map.of("replicas", 2, "readyReplicas", 1));
    }

    @Test
    void 멀티클라우드가_아닌_앱의_비율은_404() {
        when(agents.multiCloud("solo")).thenReturn(false);

        assertThat(controller.traffic("solo").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void 스키마는_GCP에서만_본다() {
        when(agents.schema(anyString())).thenReturn(Optional.empty());
        gcp.expect(requestTo(startsWith("http://gcp-cicd/api/deployments/blog/schema")))
                .andRespond(withSuccess("{\"engine\":\"pgroll\"}", MediaType.APPLICATION_JSON));

        assertThat(controller.schema("blog").getBody()).contains("pgroll");
        gcp.verify();
    }
}
