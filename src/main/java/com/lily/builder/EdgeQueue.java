package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * 엣지 쓰기 큐 ({@code edge/queue.js}). PC 장애 때 앱 주인이 등록한 경로의 POST 를 앱마다 하나인 Durable Object 에 쌓고,
 * PC 가 돌아오면 받은 순서대로 다시 보낸다.
 *
 * <pre>
 * 켜기   PLATFORM_EDGE_QUEUE_KEY=(base64 32바이트). 쌓아 둔 헤더·본문 암호화 키이고, 관리 토큰도 여기서 만든다
 * 업로드 Worker 에 DO 바인딩 QUEUE(WriteQueue), 비밀값 QUEUE_KEY, 관리 주소 QUEUE_ADMIN_HOST 를 넣는다.
 *        SQLite DO 마이그레이션은 Worker 에 아직 없을 때만 보낸다
 * 관리   https://lily-edge-queue.{zone}/apps/{app}.{zone}/queue      GET 상태, DELETE 지우기
 *        https://lily-edge-queue.{zone}/apps/{app}.{zone}/queue/config PUT {"paths": [...]}
 * </pre>
 *
 * 등록 경로는 앱의 DO 에 남는다. 다시 배포해도 그대로다. 키가 비거나 32바이트가 아니면 바인딩 없이 올라가고 Worker 는 지금처럼 동작한다
 * ({@code queue.js} 는 모듈로만 들어간다).
 */
@Component
public class EdgeQueue {

    private static final Logger log = LoggerFactory.getLogger(EdgeQueue.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String MODULE = "queue.js";
    static final String MODULE_RESOURCE = "/edge/queue.js";
    static final String CLASS_NAME = "WriteQueue";
    /** WriteQueue 를 SQLite DO 로 만든 마이그레이션 */
    static final String MIGRATION_TAG = "lily-queue-v1";
    /** 관리 주소 {label}.{zone}. Worker 라우트만 있고 오리진은 없다 */
    static final String ADMIN_LABEL = "lily-edge-queue";

    /** 관리 주소 호출 */
    interface Admin {
        Reply call(String method, String url, String token, String body);
    }

    record Reply(int status, String body) {
    }

    private final String zoneName;
    private final String key;
    private final Admin admin;

    @Autowired
    public EdgeQueue(PlatformProperties props, @Value("${lily.builder.platform.edge.queue.key:}") String key) {
        this(props.cloudflare().zoneName(), key, new HttpAdmin());
    }

    EdgeQueue(String zoneName, String key, Admin admin) {
        String zone = zoneName == null ? "" : zoneName.trim().toLowerCase();
        this.zoneName = zone.endsWith(".") ? zone.substring(0, zone.length() - 1) : zone;
        this.key = key == null ? "" : key.trim();
        this.admin = admin;
        if (!this.key.isEmpty() && !validKey(this.key)) {
            log.warn("edge write queue disabled: PLATFORM_EDGE_QUEUE_KEY must be base64 of 32 bytes");
        }
    }

    static EdgeQueue disabled() {
        return new EdgeQueue("", "", (method, url, token, body) -> {
            throw new IllegalStateException("엣지 쓰기 큐가 꺼져 있다");
        });
    }

    public boolean enabled() {
        return !zoneName.isEmpty() && validKey(key);
    }

    String adminHost() {
        return ADMIN_LABEL + "." + zoneName;
    }

    /** queue.js 의 adminToken 과 같다: SHA-256("lily-edge-admin:" + QUEUE_KEY) hex */
    String adminToken() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("lily-edge-admin:" + key).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Worker 업로드 metadata 에 큐 바인딩을 더한다
     *
     * @param migrated Worker 에 {@link #MIGRATION_TAG} 가 이미 적용됐는가. 같은 마이그레이션을 다시 보내면 업로드가 거절된다
     */
    void bind(ObjectNode metadata, boolean migrated) {
        ArrayNode bindings = metadata.putArray("bindings");
        ObjectNode queue = bindings.addObject();
        queue.put("type", "durable_object_namespace");
        queue.put("name", "QUEUE");
        queue.put("class_name", CLASS_NAME);
        ObjectNode secret = bindings.addObject();
        secret.put("type", "secret_text");
        secret.put("name", "QUEUE_KEY");
        secret.put("text", key);
        ObjectNode host = bindings.addObject();
        host.put("type", "plain_text");
        host.put("name", "QUEUE_ADMIN_HOST");
        host.put("text", adminHost());
        if (!migrated) {
            ObjectNode migrations = metadata.putObject("migrations");
            migrations.put("new_tag", MIGRATION_TAG);
            migrations.set("new_sqlite_classes", MAPPER.createArrayNode().add(CLASS_NAME));
        }
    }

    /** 등록 경로, 상태별 건수(queued·sent·failed), 최근 요청 50건 */
    public JsonNode state(String app) {
        return json(admin.call("GET", queueUrl(app), adminToken(), null));
    }

    /**
     * 등록 경로를 바꾼다. 빈 목록이면 새 POST 는 쌓지 않는다 (이미 쌓인 요청은 계속 보낸다)
     *
     * @throws IllegalArgumentException 경로가 맞지 않다 (Worker 가 400)
     */
    public JsonNode configure(String app, List<String> paths) {
        return configure(app, paths, null);
    }

    /**
     * 앱의 엣지 설정에서 준 값만 바꾼다. 배포 화면의 체크박스가 여기로 온다
     *
     * @param paths    쓰기 큐 등록 경로 (켜기 = ["/"], 끄기 = []). null 이면 그대로
     * @param snapshot 읽기 사본(Cache API)을 쓰는가. null 이면 그대로
     * @throws IllegalArgumentException 값이 맞지 않다 (Worker 가 400)
     */
    public JsonNode configure(String app, List<String> paths, Boolean snapshot) {
        ObjectNode change = MAPPER.createObjectNode();
        if (paths != null) {
            change.set("paths", MAPPER.valueToTree(paths));
        }
        if (snapshot != null) {
            change.put("snapshot", snapshot);
        }
        Reply reply = admin.call("PUT", queueUrl(app) + "/config", adminToken(), change.toString());
        if (reply.status() == 400) {
            throw new IllegalArgumentException("엣지 설정이 맞지 않다: " + reply.body());
        }
        return json(reply);
    }

    /**
     * 멀티클라우드 앱의 GCP 비율. worker.js 가 요청마다 이 비율로 {app}-gcp·{app}-aws 를 고른다
     *
     * @param gcpPercent 0~100. null 이면 멀티클라우드를 끈다
     * @throws IllegalArgumentException 값이 맞지 않다 (Worker 가 400)
     */
    public JsonNode split(String app, Integer gcpPercent) {
        ObjectNode change = MAPPER.createObjectNode();
        if (gcpPercent == null) {
            change.putNull("gcpPercent");
        } else {
            change.put("gcpPercent", gcpPercent);
        }
        Reply reply = admin.call("PUT", queueUrl(app) + "/config", adminToken(), change.toString());
        if (reply.status() == 400) {
            throw new IllegalArgumentException("비율이 맞지 않다: " + reply.body());
        }
        return json(reply);
    }

    /** 앱을 지운 뒤. 쌓인 요청과 등록 경로를 지운다 */
    public void clear(String app) {
        json(admin.call("DELETE", queueUrl(app), adminToken(), null));
    }

    private String queueUrl(String app) {
        String label = app == null ? "" : app.trim().toLowerCase();
        if (!label.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")) {
            throw new IllegalArgumentException("앱 이름이 DNS 이름이 아니다: " + app);
        }
        return "https://" + adminHost() + "/apps/" + label + "." + zoneName + "/queue";
    }

    private static JsonNode json(Reply reply) {
        if (reply.status() != 200) {
            throw new IllegalStateException("쓰기 큐 관리 주소가 " + reply.status() + " 을 돌려줬다");
        }
        try {
            return MAPPER.readTree(reply.body());
        } catch (Exception e) {
            throw new IllegalStateException("쓰기 큐 관리 주소의 응답이 JSON 이 아니다");
        }
    }

    private static boolean validKey(String key) {
        try {
            return Base64.getDecoder().decode(key).length == 32;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static final class HttpAdmin implements Admin {

        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        @Override
        public Reply call(String method, String url, String token, String body) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                return new Reply(response.statusCode(), response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("쓰기 큐 관리 주소 호출이 중단되었다");
            } catch (Exception e) {
                throw new IllegalStateException("쓰기 큐 관리 주소에 닿지 못했다: " + e.getMessage());
            }
        }
    }
}
