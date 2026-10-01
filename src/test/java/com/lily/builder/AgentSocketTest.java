package com.lily.builder;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 소켓으로 붙어 본다: 토큰 발급 → 접속 → hello → 상태 조회 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "lily.builder.registry=reg:5000",
        "lily.builder.agents.token-secret=0123456789abcdef0123456789abcdef"})
class AgentSocketTest {

    /** 배포 이력은 DynamoDB 대신 메모리에 */
    @TestConfiguration
    static class Store {
        @Bean
        @Primary
        BuildStore memoryStore() {
            return new InMemoryBuildStore();
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate http;
    @Autowired
    AgentHub hub;

    @Test
    void 발급한_토큰으로_붙으면_연결_상태와_hello_가_보인다() throws Exception {
        ResponseEntity<Map> issued = http.postForEntity("/api/agents", null, Map.class);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String key = (String) issued.getBody().get("key");
        String token = (String) issued.getBody().get("token");

        WebSocketSession session = new StandardWebSocketClient()
                .execute(new TextWebSocketHandler(), "ws://localhost:" + port + AgentSocket.PATH + "?token=" + token)
                .get(5, TimeUnit.SECONDS);
        session.sendMessage(new TextMessage("""
                {"type":"hello","agentId":"edge-1","publicUrl":"","version":"0.1.0","database":true}"""));

        for (int i = 0; i < 50 && hub.agentId(key) == null; i++) {
            Thread.sleep(100);
        }
        Map<?, ?> status = http.getForObject("/api/agents/" + key, Map.class);
        assertThat(status.get("connected")).isEqualTo(true);
        assertThat(status.get("agentId")).isEqualTo("edge-1");
        assertThat(status.get("database")).isEqualTo(true);

        session.close();
        for (int i = 0; i < 50 && hub.connected(key); i++) {
            Thread.sleep(100);
        }
        assertThat(http.getForObject("/api/agents/" + key, Map.class).get("connected")).isEqualTo(false);
    }

    @Test
    void 키_형식이_아니면_상태_조회는_404() {
        assertThat(http.getForEntity("/api/agents/not-a-key", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 토큰이_틀리면_핸드셰이크에서_거절한다() {
        assertThatThrownBy(() -> new StandardWebSocketClient()
                .execute(new TextWebSocketHandler(), "ws://localhost:" + port + AgentSocket.PATH + "?token=abcdefabcdef.bad")
                .get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void 연결되지_않은_에이전트로_배포하면_Build_가_실패로_남는다() {
        String key = (String) http.postForEntity("/api/agents", null, Map.class).getBody().get("key");

        ResponseEntity<Map> accepted = http.postForEntity("/api/agents/" + key + "/builds", Map.of(
                "repoUrl", "https://github.com/org/repo", "appName", "blog"), Map.class);

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String id = (String) accepted.getBody().get("id");
        for (int i = 0; i < 50; i++) {
            if ("FAILED".equals(http.getForObject("/api/builds/" + id, Map.class).get("status"))) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("FAILED 가 되지 않았다");
    }
}
