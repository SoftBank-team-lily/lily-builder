package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * 에이전트가 붙는 소켓 {@code /api/agents/connect?token=...}. lily-on-premise 의 CONTROL_PLANE_URL 이 이 주소다.
 *
 * <pre>
 * 에이전트 → hello  {"type":"hello","agentId":"edge-1","publicUrl":"","version":"0.1.0","database":false}
 * builder  → job    {"type":"job","id":"{빌드 id}","repoUrl":...}   (AgentDeployService)
 * 에이전트 → status {"type":"status","id":"{빌드 id}","status":"BUILDING","line":"...","url":"..."}
 * </pre>
 *
 * 외부에는 이 경로만 연다 (lily-loadbalancer manifests/lily-builder.yaml). 토큰이 틀리면 핸드셰이크에서 401.
 */
@Configuration
@EnableWebSocket
@EnableScheduling
public class AgentSocket implements WebSocketConfigurer {

    static final String PATH = "/api/agents/connect";
    private static final String KEY = "agentKey";
    private static final Logger log = LoggerFactory.getLogger(AgentSocket.class);

    private final AgentTokens tokens;
    private final AgentHub hub;
    private final AgentDeployService deploys;
    private final ObjectMapper json = new ObjectMapper();

    public AgentSocket(AgentTokens tokens, AgentHub hub, AgentDeployService deploys) {
        this.tokens = tokens;
        this.hub = hub;
        this.deploys = deploys;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 에이전트는 브라우저가 아니라 Origin 이 없다. 인증은 토큰으로 한다
        registry.addHandler(new Handler(), PATH).addInterceptors(new TokenCheck()).setAllowedOrigins("*");
    }

    private final class TokenCheck implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler handler, Map<String, Object> attributes) {
            String token = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("token");
            return tokens.verify(token).map(key -> {
                attributes.put(KEY, key);
                return true;
            }).orElseGet(() -> {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            });
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Exception exception) {
        }
    }

    private final class Handler extends TextWebSocketHandler {

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            hub.opened(key(session), session);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            String key = key(session);
            try {
                JsonNode node = json.readTree(message.getPayload());
                hub.seen(key);
                switch (node.path("type").asText()) {
                    case "hello" -> hub.hello(key, node.path("agentId").asText(null), node.path("publicUrl").asText(""),
                            node.path("database").asBoolean(false));
                    case "status" -> deploys.agentStatus(key, node.path("id").asText(), node.path("status").asText(),
                            node.path("line").asText(""), node.path("url").asText(""));
                    default -> log.debug("agent message ignored: key={} type={}", key, node.path("type").asText());
                }
            } catch (Exception e) {
                log.warn("agent message rejected: key={} message={}", key, e.getMessage());
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            hub.closed(key(session), session);
        }

        private String key(WebSocketSession session) {
            return (String) session.getAttributes().get(KEY);
        }
    }
}
