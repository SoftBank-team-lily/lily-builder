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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 에이전트가 붙는 소켓 {@code /api/agents/connect?token=...}. lily-on-premise 의 CONTROL_PLANE_URL 이 이 주소다.
 *
 * <pre>
 * 에이전트 → hello  {"type":"hello","agentId":"edge-1","publicUrl":"","version":"0.1.0","database":false,
 *                    "databaseModes":["cloud","local","external"]}
 * builder  → welcome {"type":"welcome","tunnelAgentId":"agent-{key}","cloudflare":{...},"database":{...}}  플랫폼 연결
 *            database.reverseHost·reversePort: 온프레미스 DB 를 클라우드에 여는 역방향 터널 (배스천 사설 IP 의 이 에이전트 포트)
 * 에이전트 → cloudflare {"type":"cloudflare","rid":"..","method":"GET","path":"/zones/..."}  → 같은 rid 로 {"ok":..,"result":..}
 * builder  → job    {"type":"job","id":"{빌드 id}","repoUrl":...}   (AgentDeployService)
 * 에이전트 → status {"type":"status","id":"{빌드 id}","status":"BUILDING","line":"...","url":"..."}
 * welcome.burst {"ingressHost":..,"ingressPort":80,"cloudOrigin":..}  플랫폼 연결 에이전트가 버스팅·거점 전환을 켤 수 있다
 * 에이전트 → burst  {"type":"burst","rid":"..","method":"POST","path":"/api/burst/apps/{app}/standby","body":{..}}
 *            → 같은 rid 로 {"ok":..,"result":..} (AgentBurst)
 * builder  → burst  {"type":"burst","app":"..","enabled":true,"cloudPercent":30}   화면에서 정한 버스팅 설정
 * 에이전트 → burst-state {"type":"burst-state","app":..,"enabled":..,"phase":..,"home":..}  몇 초마다
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
    private final AgentCloudflare cloudflare;
    private final TunnelCertificates certificates;
    private final AgentDatabasePorts ports;
    private final AgentBurst burst;
    private final PlatformProperties.Burst burstSettings;
    private final ObjectMapper json = new ObjectMapper();
    /** Cloudflare 중계는 수 초 걸린다. 소켓 수신 스레드를 막지 않게 따로 돌린다 */
    private final ExecutorService relay = Executors.newVirtualThreadPerTaskExecutor();

    public AgentSocket(AgentTokens tokens, AgentHub hub, AgentDeployService deploys,
                       AgentCloudflare cloudflare, TunnelCertificates certificates, AgentDatabasePorts ports,
                       AgentBurst burst, PlatformProperties platform) {
        this.burst = burst;
        this.burstSettings = platform.burst();
        this.tokens = tokens;
        this.hub = hub;
        this.deploys = deploys;
        this.cloudflare = cloudflare;
        this.certificates = certificates;
        this.ports = ports;
    }

    /**
     * 플랫폼 연결 에이전트에 공개 주소용 존과 DB 터널 인증서를 준다. 플랫폼에 없는 것은 싣지 않는다
     * (에이전트는 존이 없으면 quick tunnel, 인증서가 없으면 DB 없는 앱만 받는다).
     */
    void welcome(String key, JsonNode hello) {
        if (!hello.path("platform").asBoolean(false) && !hello.hasNonNull("sshPublicKey")) {
            return;
        }
        Map<String, Object> welcome = new LinkedHashMap<>();
        welcome.put("type", "welcome");
        welcome.put("tunnelAgentId", AgentCloudflare.tunnelAgentId(key));
        if (hello.path("platform").asBoolean(false) && cloudflare.enabled()) {
            welcome.put("cloudflare", cloudflare.zone());
            if (burstSettings.configured()) {
                Map<String, Object> cloud = new LinkedHashMap<>();
                cloud.put("ingressHost", burstSettings.ingressHost());
                cloud.put("ingressPort", burstSettings.ingressPort());
                cloud.put("cloudOrigin", burstSettings.origin());
                // 에이전트 컨테이너를 새로 만들면 마지막 앱을 잊는다. 이 앱의 CNAME 으로 거점을 복구한다
                deploys.latestApp(key).ifPresent(app -> cloud.put("app", app));
                welcome.put("burst", cloud);
            }
        }
        String publicKey = hello.path("sshPublicKey").asText("");
        if (!publicKey.isBlank() && certificates.enabled()) {
            try {
                PlatformProperties.Tunnel tunnel = certificates.settings();
                Map<String, Object> database = new LinkedHashMap<>();
                database.put("sshHost", tunnel.sshHost());
                database.put("sshUser", tunnel.sshUser());
                database.put("remoteHost", tunnel.remoteHost());
                database.put("remotePort", tunnel.remotePort());
                Integer reversePort = null;
                if (ports.enabled()) {
                    try {
                        reversePort = ports.portOf(key);
                        database.put("reverseHost", tunnel.reverseHost());
                        database.put("reversePort", reversePort);
                    } catch (RuntimeException e) {
                        // 역방향 터널 없이도 RDS 터널과 내 PC DB 는 쓴다. 클라우드 대기 배포만 못 한다
                        log.warn("agent reverse port failed: key={} message={}", key, e.getMessage());
                    }
                }
                database.put("certificate", certificates.sign(key, publicKey, reversePort));
                welcome.put("database", database);
                hub.platformDatabase(key, hello.path("databaseHost").asText("172.17.0.1"),
                        hello.path("databasePort").asInt(15432));
            } catch (RuntimeException e) {
                log.warn("agent tunnel certificate failed: key={} message={}", key, e.getMessage());
            }
        }
        try {
            hub.send(key, json.writeValueAsString(welcome));
        } catch (Exception e) {
            log.warn("agent welcome failed: key={} message={}", key, e.getMessage());
        }
    }

    /** 플랫폼 연결 에이전트의 /api/burst 호출. 빌드 요청은 수 초 걸려 수신 스레드 밖에서 한다 */
    void relayBurst(String key, JsonNode request) {
        String rid = request.path("rid").asText("");
        relay.execute(() -> {
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("type", "burst");
            reply.put("rid", rid);
            try {
                reply.put("result", burst.call(key, request.path("method").asText(), request.path("path").asText(),
                        request.hasNonNull("body") ? request.get("body") : null));
                reply.put("ok", true);
            } catch (RuntimeException e) {
                log.info("agent burst call rejected: key={} {} {} → {}", key, request.path("method").asText(),
                        request.path("path").asText(), e.getMessage());
                reply.put("ok", false);
                reply.put("message", e.getMessage());
            }
            try {
                hub.send(key, json.writeValueAsString(reply));
            } catch (Exception e) {
                log.warn("agent burst reply failed: key={} message={}", key, e.getMessage());
            }
        });
    }

    void relay(String key, JsonNode request) {
        String rid = request.path("rid").asText("");
        relay.execute(() -> {
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("type", "cloudflare");
            reply.put("rid", rid);
            try {
                JsonNode result = cloudflare.call(key, request.path("method").asText(), request.path("path").asText(),
                        request.hasNonNull("body") ? request.get("body") : null);
                reply.put("ok", true);
                reply.put("result", result);
            } catch (RuntimeException e) {
                log.info("agent cloudflare call rejected: key={} {} {} → {}", key, request.path("method").asText(),
                        request.path("path").asText(), e.getMessage());
                reply.put("ok", false);
                reply.put("message", e.getMessage());
            }
            try {
                hub.send(key, json.writeValueAsString(reply));
            } catch (Exception e) {
                log.warn("agent cloudflare reply failed: key={} message={}", key, e.getMessage());
            }
        });
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
                    case "hello" -> {
                        java.util.Set<String> modes = new java.util.HashSet<>();
                        node.path("databaseModes").forEach(mode -> modes.add(mode.asText()));
                        hub.hello(key, node.path("agentId").asText(null), node.path("publicUrl").asText(""),
                                node.path("database").asBoolean(false), modes);
                        java.util.Set<String> features = new java.util.HashSet<>();
                        node.path("features").forEach(feature -> features.add(feature.asText()));
                        hub.features(key, features);
                        welcome(key, node);
                    }
                    case "cloudflare" -> relay(key, node);
                    case "burst" -> relayBurst(key, node);
                    case "burst-state" -> hub.burstState(key, node);
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
