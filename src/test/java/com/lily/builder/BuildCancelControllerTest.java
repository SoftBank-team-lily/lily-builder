package com.lily.builder;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 화면의 배포 취소 → POST /api/builds/{id}/cancel. 온프레미스 빌드는 에이전트 쪽으로 보낸다 */
class BuildCancelControllerTest {

    private final BuildService service = mock(BuildService.class);
    private final AgentDeployService agents = mock(AgentDeployService.class);
    private final BuildController controller = new BuildController(
            service, mock(ClusterApps.class), new CicdClient(RestClient.builder().baseUrl("http://cicd").build()),
            agents, AppAddress.disabled());

    private static Build build(String id, Build.Status status, String... logs) {
        return new Build(id, "blog", "https://github.com/org/blog", "main", null, null, Instant.now(), null,
                status, null, null, List.of(logs));
    }

    @Test
    void 클라우드_빌드_취소는_BuildService로_보내고_202와_CANCELLED_빌드를_돌려준다() {
        when(service.get("c1")).thenReturn(Optional.of(build("c1", Build.Status.BUILDING, "queued: x")));
        when(service.cancel("c1")).thenReturn(build("c1", Build.Status.CANCELLED));

        ResponseEntity<?> response = controller.cancel("c1");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(((Build) response.getBody()).getStatus()).isEqualTo(Build.Status.CANCELLED);
        verify(agents, never()).cancel(anyString());
    }

    @Test
    void 온프레미스_빌드_취소는_AgentDeployService로_보낸다() {
        when(service.get("o1")).thenReturn(Optional.of(
                build("o1", Build.Status.BUILDING, "queued: x branch=main target=onprem agent=a1b2c3d4e5f6")));
        when(agents.cancel("o1")).thenReturn(build("o1", Build.Status.CANCELLED));

        ResponseEntity<?> response = controller.cancel("o1");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(service, never()).cancel(anyString());
    }

    @Test
    void 취소할_수_없으면_409와_이유를_넘긴다() {
        when(service.get("c1")).thenReturn(Optional.of(build("c1", Build.Status.DEPLOYING)));
        when(service.cancel("c1")).thenThrow(new IllegalStateException("lily-cicd 가 새 버전을 띄우는 중이라 취소할 수 없다"));

        ResponseEntity<?> response = controller.cancel("c1");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat((String) response.getBody()).contains("REJECTED").contains("lily-cicd");
    }

    @Test
    void 없는_빌드를_취소하면_404() {
        when(service.get("x")).thenReturn(Optional.empty());

        assertThat(controller.cancel("x").getStatusCode().value()).isEqualTo(404);
    }
}
