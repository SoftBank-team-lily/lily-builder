package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 지금 연결된 온프레미스 에이전트. key 하나에 소켓 하나다 (같은 key 로 다시 붙으면 이전 소켓을 닫는다).
 *
 * <p>연결 정보는 메모리에만 둔다. builder 가 재시작하면 에이전트가 5초 뒤 다시 붙는다 (lily-on-premise 재연결 루프).
 */
@Component
public class AgentHub {

    private static final Logger log = LoggerFactory.getLogger(AgentHub.class);

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    void opened(String key, WebSocketSession session) {
        // 소켓 쓰기는 한 번에 하나여야 한다. 잡 전송과 ping 이 겹칠 수 있어 감싼다
        Connection connection = new Connection(
                new ConcurrentWebSocketSessionDecorator(session, 10_000, 2 * 1024 * 1024), Instant.now());
        Connection previous = connections.put(key, connection);
        if (previous != null) {
            closeQuietly(previous.session);
        }
        log.info("agent connected: key={}", key);
    }

    void closed(String key, WebSocketSession session) {
        connections.computeIfPresent(key, (k, c) -> c.session.getId().equals(session.getId()) ? null : c);
        log.info("agent disconnected: key={}", key);
    }

    /** 에이전트가 처음 보내는 hello */
    void hello(String key, String agentId, String publicUrl, boolean database) {
        hello(key, agentId, publicUrl, database, Set.of());
    }

    /** 에이전트가 받는 메시지 종류 (burst, home). 이 필드 전의 에이전트는 비어 있다 */
    void features(String key, Set<String> features) {
        Connection connection = connections.get(key);
        if (connection != null) {
            connection.features = Set.copyOf(features);
        }
    }

    public boolean supports(String key, String feature) {
        Connection connection = connections.get(key);
        return connection != null && connection.features.contains(feature);
    }

    /** 에이전트가 몇 초마다 보내는 버스팅·거점 상태 (lily-on-premise burst-state) */
    void burstState(String key, JsonNode state) {
        Connection connection = connections.get(key);
        if (connection != null) {
            connection.burstState = state;
        }
    }

    /** 화면이 보는 버스팅 상태. 에이전트가 이 앱의 상태를 아직 보내지 않았으면 state 가 비어 있다 */
    public Burst burst(String key, String app) {
        Connection connection = connections.get(key);
        if (connection == null || !connection.session.isOpen()) {
            return new Burst(false, false, null, Map.of(), "");
        }
        JsonNode state = connection.burstState;
        String reported = state == null ? "" : state.path("app").asText("");
        boolean mine = state != null && (reported.isBlank() || reported.equals(app));
        return new Burst(true, connection.features.contains("burst"), mine ? state : null, Map.of(), reported);
    }

    /** @param databaseModes 에이전트가 받을 수 있는 DB 위치 (local, external). 이 필드 전의 에이전트는 비어 있다 */
    void hello(String key, String agentId, String publicUrl, boolean database, Set<String> databaseModes) {
        Connection connection = connections.get(key);
        if (connection != null) {
            connection.agentId = agentId;
            connection.publicUrl = publicUrl;
            connection.database = database;
            connection.databaseModes = Set.copyOf(databaseModes);
            connection.lastSeenAt = Instant.now();
        }
        log.info("agent hello: key={} agentId={} database={} modes={}", key, agentId, database, databaseModes);
    }

    void seen(String key) {
        Connection connection = connections.get(key);
        if (connection != null) {
            connection.lastSeenAt = Instant.now();
        }
    }

    public boolean connected(String key) {
        Connection connection = connections.get(key);
        return connection != null && connection.session.isOpen();
    }

    /** 플랫폼이 DB 터널 인증서를 줬다. 잡에 터널 주소 기준 DB 접속 정보를 실어야 한다 */
    void platformDatabase(String key, String host, int port) {
        Connection connection = connections.get(key);
        if (connection != null) {
            connection.database = true;
            connection.tunnel = new Tunnel(host, port);
        }
    }

    public boolean platformDatabase(String key) {
        Connection connection = connections.get(key);
        return connection != null && connection.tunnel != null;
    }

    public Tunnel tunnel(String key) {
        Connection connection = connections.get(key);
        return connection == null ? null : connection.tunnel;
    }

    /** DB 터널이 있어 database 가 있는 잡을 받을 수 있다 */
    public boolean supportsDatabase(String key) {
        Connection connection = connections.get(key);
        return connection != null && connection.database;
    }

    /** 에이전트가 이 DB 위치(local, external)를 처리한다 */
    public boolean supportsDatabaseMode(String key, String mode) {
        Connection connection = connections.get(key);
        return connection != null && connection.databaseModes.contains(mode);
    }

    public String agentId(String key) {
        Connection connection = connections.get(key);
        return connection == null ? null : connection.agentId;
    }

    /** @throws IllegalStateException 연결이 없거나 보내지 못했다 */
    public void send(String key, String json) {
        Connection connection = connections.get(key);
        if (connection == null || !connection.session.isOpen()) {
            throw new IllegalStateException("에이전트가 연결돼 있지 않다");
        }
        try {
            connection.session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            throw new IllegalStateException("에이전트로 보내지 못했다: " + e.getMessage(), e);
        }
    }

    public Status status(String key) {
        Connection connection = connections.get(key);
        if (connection == null || !connection.session.isOpen()) {
            return new Status(key, false, null, null, false, null, null);
        }
        return new Status(key, true, connection.agentId, blankToNull(connection.publicUrl), connection.database,
                connection.connectedAt, connection.lastSeenAt);
    }

    /** ALB 는 60초 동안 오가는 게 없으면 연결을 끊는다. 그보다 자주 ping 을 보낸다 */
    @Scheduled(fixedDelayString = "#{${lily.builder.agents.ping-seconds:25} * 1000}")
    void ping() {
        connections.forEach((key, connection) -> {
            try {
                if (connection.session.isOpen()) {
                    connection.session.sendMessage(new PingMessage());
                }
            } catch (IOException | RuntimeException e) {
                log.debug("agent ping failed: key={} message={}", key, e.getMessage());
            }
        });
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (IOException ignored) {
            // 이미 끊긴 소켓
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static final class Connection {
        private final WebSocketSession session;
        private final Instant connectedAt;
        private volatile Instant lastSeenAt;
        private volatile String agentId;
        private volatile String publicUrl;
        private volatile boolean database;
        private volatile Tunnel tunnel;
        private volatile Set<String> databaseModes = Set.of();
        private volatile Set<String> features = Set.of();
        private volatile JsonNode burstState;

        Connection(WebSocketSession session, Instant connectedAt) {
            this.session = session;
            this.connectedAt = connectedAt;
            this.lastSeenAt = connectedAt;
        }
    }

    /**
     * @param connected 에이전트가 붙어 있다
     * @param supported 에이전트가 버스팅 설정 메시지를 받는 판이다
     * @param state     에이전트가 보낸 burst-state 그대로 (enabled, cloudPercent, phase, home, ...)
     */
    /**
     * @param agentApp 에이전트가 지금 다루는 앱. 이 앱이 아니면 state 가 비고, 화면이 "다른 앱을 돌리는 중"을 보인다
     */
    public record Burst(boolean connected, boolean supported, JsonNode state, Map<String, Progress> builds,
                        String agentApp) {

        public Burst withBuilds(Map<String, Progress> builds) {
            return new Burst(connected, supported, state, builds, agentApp);
        }
    }

    /**
     * 에이전트가 기다리는 클라우드 빌드의 진행 (화면 진행 표시용).
     * @param status QUEUED · BUILDING · DEPLOYING · SUCCEEDED · FAILED · ROLLED_BACK
     * @param line   빌드 로그 마지막 줄
     */
    public record Progress(String id, String status, String line, Instant createdAt, Instant updatedAt) {
    }

    /** 에이전트 컨테이너가 DB 터널을 여는 주소 (앱 컨테이너가 이 주소로 DB 에 붙는다) */
    public record Tunnel(String host, int port) {
    }

    /**
     * @param publicUrl 에이전트 프록시의 공개 주소 (앱을 배포하기 전에는 비어 있을 수 있다)
     * @param database  DB 터널이 있다
     */
    public record Status(String key, boolean connected, String agentId, String publicUrl, boolean database,
                         Instant connectedAt, Instant lastSeenAt) {
    }
}
