package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 플랫폼 연결 에이전트의 /api/burst 중계: 이 에이전트로 배포한 앱만 */
class AgentBurstTest {

    private static final String KEY = "a1b2c3d4e5f6";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentDeployService deploys = mock(AgentDeployService.class);
    private final ProvisionerClient provisioner = mock(ProvisionerClient.class);
    private final AgentBurst burst = new AgentBurst(mock(BuildService.class), mock(CicdClient.class), deploys,
            Validation.buildDefaultValidatorFactory().getValidator(), provisioner);

    @Test
    void 거점_전환_DB_이전용_RDS_접속_정보를_터널_주소로_준다() throws Exception {
        when(deploys.ownedBy(KEY, "blog")).thenReturn(true);
        when(provisioner.ensure("blog", "postgres", "172.17.0.1", 15432)).thenReturn(
                new ProvisionerClient.Connection("db-1", Map.of("DATABASE_URL", "postgresql://blog:p@172.17.0.1:15432/blog")));

        JsonNode result = burst.call(KEY, "POST", "/api/burst/apps/blog/database",
                JSON.readTree("{\"engine\":\"postgres\",\"host\":\"172.17.0.1\",\"port\":15432}"));

        assertThat(result.path("env").path("DATABASE_URL").asText()).contains("172.17.0.1:15432");
    }

    @Test
    void pgroll_요청이면_RDS_접속_정보를_주고_provisioner로_pgroll을_켠다() throws Exception {
        when(deploys.ownedBy(KEY, "blog")).thenReturn(true);
        when(provisioner.ensure("blog", "postgres", "172.17.0.1", 15432)).thenReturn(
                new ProvisionerClient.Connection("db-1", Map.of("DATABASE_URL", "postgresql://blog:p@172.17.0.1:15432/blog")));

        burst.call(KEY, "POST", "/api/burst/apps/blog/database",
                JSON.readTree("{\"engine\":\"postgres\",\"host\":\"172.17.0.1\",\"port\":15432,\"pgroll\":true}"));
        burst.call(KEY, "POST", "/api/burst/apps/blog/database",
                JSON.readTree("{\"engine\":\"postgres\",\"host\":\"172.17.0.1\",\"port\":15432}"));

        verify(provisioner, times(1)).enablePgroll("blog");
    }

    @Test
    void 다른_에이전트의_앱이나_이상한_주소는_거절한다() throws Exception {
        when(deploys.ownedBy(KEY, "blog")).thenReturn(true);
        String body = "{\"engine\":\"postgres\",\"host\":\"172.17.0.1\",\"port\":15432}";

        assertThatThrownBy(() -> burst.call(KEY, "POST", "/api/burst/apps/other/database", JSON.readTree(body)))
                .hasMessageContaining("이 에이전트로 배포한 앱이 아니다");
        assertThatThrownBy(() -> burst.call(KEY, "POST", "/api/burst/apps/blog/database",
                JSON.readTree(body.replace("172.17.0.1", "evil host;"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> burst.call(KEY, "POST", "/api/burst/apps/blog/database",
                JSON.readTree(body.replace("postgres", "oracle"))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(provisioner, never()).ensure(anyString(), anyString(), anyString(), anyInt());
    }
}
