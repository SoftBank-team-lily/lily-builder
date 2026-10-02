package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PC 장애 시 공개 주소를 클라우드로: 끊김 유예, 대상 조건, 전환 순서 */
class AgentFailoverTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "a1b2c3d4e5f6";
    private static final String APP = "blog-1b62c0";
    private static final String TUNNEL = "11111111-2222-3333-4444-555555555555.cfargotunnel.com";
    private static final String ORIGIN = "alb.example.net";

    private final AgentHub hub = mock(AgentHub.class);
    private final AgentDeployService deploys = mock(AgentDeployService.class);
    private final CicdClient cicd = mock(CicdClient.class);
    private final List<String> dns = new ArrayList<>();
    private String content = TUNNEL;
    private final Instant down = Instant.parse("2026-10-02T00:00:00Z");
    private Instant now = down;
    private AgentFailover failover;

    @BeforeEach
    void setUp() {
        AppAddress addresses = new AppAddress(
                new PlatformProperties.Cloudflare("token", "acc", "zone", "lilycloud.kr"), ORIGIN,
                (method, path, body) -> {
                    if ("GET".equals(method)) {
                        return JSON.createArrayNode().add(JSON.createObjectNode()
                                .put("id", "0123456789abcdef0123456789abcdef").put("type", "CNAME")
                                .put("content", content).put("proxied", true));
                    }
                    dns.add(method + " " + body.path("content").asText());
                    content = body.path("content").asText();
                    return JSON.createObjectNode();
                });
        failover = new AgentFailover(hub, deploys, cicd, addresses, Duration.ofSeconds(60), 2, Duration.ZERO,
                () -> now, Runnable::run);
        when(hub.disconnectedSince()).thenReturn(Map.of(KEY, down));
        when(hub.lastState(KEY)).thenReturn(state("ONPREM", true, true, "cloud"));
        when(deploys.ownedBy(KEY, APP)).thenReturn(true);
        when(cicd.status(APP)).thenReturn(new CicdClient.AppStatus(APP, "default", "blue", 2, 1));
    }

    @Test
    void 유예_시간이_지나면_클라우드를_늘리고_주소를_ALB_로_돌린다() {
        now = down.plusSeconds(59);
        failover.check();
        assertThat(dns).isEmpty();

        now = down.plusSeconds(61);
        failover.check();

        verify(cicd).scale(APP, 2);
        assertThat(dns).containsExactly("PUT " + ORIGIN);
    }

    @Test
    void 같은_끊김은_한_번만_처리한다() {
        now = down.plusSeconds(61);
        failover.check();
        failover.check();

        assertThat(dns).hasSize(1);
    }

    @Test
    void 기다리는_동안_PC_가_돌아오면_주소를_두고_대기_수로_되돌린다() {
        when(hub.connected(KEY)).thenReturn(true);
        now = down.plusSeconds(61);

        failover.check();

        verify(cicd).scale(APP, 1);
        assertThat(dns).isEmpty();
    }

    @Test
    void 클라우드_Pod_가_준비되지_않으면_주소를_바꾸지_않는다() {
        when(cicd.status(APP)).thenReturn(new CicdClient.AppStatus(APP, "default", "blue", 2, 0));
        now = down.plusSeconds(61);

        failover.check();

        assertThat(dns).isEmpty();
    }

    @Test
    void 대상이_아니면_건드리지_않는다() {
        assertThat(failover.skip(KEY, null)).isPresent();
        assertThat(failover.skip(KEY, state("ONPREM", false, false, "cloud"))).get().asString().contains("대기 Pod");
        assertThat(failover.skip(KEY, state("CLOUD", true, true, ""))).get().asString().contains("거점");
        assertThat(failover.skip(KEY, state("ONPREM", true, true, "local"))).get().asString().contains("DB");
        assertThat(failover.skip(KEY, state("ONPREM", true, true, ""))).isEmpty();

        when(hub.lastState(KEY)).thenReturn(state("ONPREM", true, true, "local"));
        now = down.plusSeconds(61);
        failover.check();
        verify(cicd, never()).scale(anyString(), anyInt());
        assertThat(dns).isEmpty();
    }

    @Test
    void 주소가_이미_내_PC_가_아니면_바꾸지_않는다() {
        content = ORIGIN;
        now = down.plusSeconds(61);

        failover.check();

        assertThat(dns).isEmpty();
    }

    private static JsonNode state(String home, boolean enabled, boolean warm, String databaseMode) {
        return JSON.createObjectNode().put("app", APP).put("enabled", enabled).put("warm", warm)
                .put("home", home).put("databaseMode", databaseMode);
    }
}
