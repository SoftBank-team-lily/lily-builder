package com.lily.builder;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 화면의 롤백 버튼 → lily-builder → lily-cicd. 상태 코드와 본문을 그대로 넘긴다 */
class RollbackProxyTest {

    private final RestClient.Builder http = RestClient.builder().baseUrl("http://cicd");
    private final MockRestServiceServer cicd = MockRestServiceServer.bindTo(http).build();
    private final BuildController controller = new BuildController(
            mock(BuildService.class), mock(ClusterApps.class), new CicdClient(http.build()));

    @Test
    void 롤백_결과를_그대로_돌려준다() {
        cicd.expect(requestTo("http://cicd/api/deployments/blog/rollback"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"appOnly\":false}"))
                .andRespond(withSuccess("{\"status\":\"ROLLED_BACK\",\"schemaVersion\":\"2\"}", MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = controller.rollback("blog", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("ROLLED_BACK");
        cicd.verify();
    }

    @Test
    void 거절은_409와_이유를_그대로_넘긴다() {
        cicd.expect(requestTo("http://cicd/api/deployments/blog/rollback"))
                .andExpect(content().json("{\"appOnly\":true}"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .body("{\"status\":\"REJECTED\",\"message\":\"롤백할 수 없다\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = controller.rollback("blog", new BuildController.RollbackRequest(true));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("롤백할 수 없다");
    }

    @Test
    void 릴리스_상태를_넘기고_잘못된_이름은_부르지_않는다() {
        cicd.expect(requestTo("http://cicd/api/deployments/blog"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"activeSlot\":\"green\",\"rollbackAvailable\":true}", MediaType.APPLICATION_JSON));

        assertThat(controller.release("blog").getBody()).contains("\"activeSlot\":\"green\"");
        assertThat(controller.release("../x").getStatusCode().value()).isEqualTo(400);
        assertThat(controller.rollback("Blog", null).getStatusCode().value()).isEqualTo(400);
        cicd.verify();
    }
}
