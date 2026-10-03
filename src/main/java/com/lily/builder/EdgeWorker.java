package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PC 장애 때 엣지에서 요청을 클라우드로 다시 보내는 Cloudflare Worker ({@code edge/worker.js}).
 *
 * <pre>
 * 평소     {app}.{zone} → Worker → CNAME 내용물(PC 터널) → 에이전트 프록시
 * PC 장애  {app}.{zone} → Worker → 530 → {app}-cloud.{zone} → ALB → ingress-nginx → 클라우드 대기 Pod
 * </pre>
 *
 * 클라우드 대기 배포가 끝난 앱에만 라우트 {@code {app}.{zone}/*} 와 클라우드 주소 {@code {app}-cloud.{zone}}(ALB 프록시 CNAME)를 둔다.
 * 거점 전환·CNAME 장애 전환({@link AgentFailover})은 그대로다. 라우트를 지우면 Worker 없이 지금과 같다.
 * 쓰기 큐({@link EdgeQueue})가 켜져 있으면 DO 바인딩을 넣어 올리고, 관리 주소 라우트를 둔다.
 */
@Component
public class EdgeWorker {

    private static final Logger log = LoggerFactory.getLogger(EdgeWorker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String CLOUD_SUFFIX = "-cloud";
    static final String SCRIPT_RESOURCE = "/edge/worker.js";

    /** 스크립트 업로드 (multipart). JSON 호출과 따로 둔다. modules 의 첫 항목이 main_module 이다 */
    interface Uploader {
        void upload(String accountId, String scriptName, String metadata, Map<String, String> modules);
    }

    private final PlatformProperties.Cloudflare settings;
    private final PlatformProperties.Edge edge;
    private final String origin;
    private final String extraOrigin;
    private final AgentCloudflare.Api api;
    private final Uploader uploader;
    private final EdgeQueue queue;

    @Autowired
    public EdgeWorker(PlatformProperties props, CloudClients clouds, EdgeQueue queue) {
        this(props.cloudflare(), props.edge(), props.burst().origin(),
                new AgentCloudflare.HttpApi(props.cloudflare().apiToken()), new HttpUploader(props.cloudflare().apiToken()),
                clouds.origin(), queue);
    }

    EdgeWorker(PlatformProperties.Cloudflare settings, PlatformProperties.Edge edge, String origin,
               AgentCloudflare.Api api, Uploader uploader) {
        this(settings, edge, origin, api, uploader, "", EdgeQueue.disabled());
    }

    EdgeWorker(PlatformProperties.Cloudflare settings, PlatformProperties.Edge edge, String origin,
               AgentCloudflare.Api api, Uploader uploader, String extraOrigin) {
        this(settings, edge, origin, api, uploader, extraOrigin, EdgeQueue.disabled());
    }

    EdgeWorker(PlatformProperties.Cloudflare settings, PlatformProperties.Edge edge, String origin,
               AgentCloudflare.Api api, Uploader uploader, String extraOrigin, EdgeQueue queue) {
        this.settings = settings;
        this.edge = edge;
        this.origin = origin == null ? "" : origin;
        this.extraOrigin = extraOrigin == null ? "" : extraOrigin;
        this.api = api;
        this.uploader = uploader;
        this.queue = queue;
    }

    static EdgeWorker disabled() {
        return new EdgeWorker(new PlatformProperties.Cloudflare("", "", "", ""), new PlatformProperties.Edge(false, "lily-edge"),
                "", (method, path, body) -> {
                    throw new IllegalStateException("엣지 Worker 가 꺼져 있다");
                }, (account, name, metadata, modules) -> {
                    throw new IllegalStateException("엣지 Worker 가 꺼져 있다");
                });
    }

    public boolean enabled() {
        return edge.enabled() && settings.configured() && !origin.isBlank();
    }

    /** 기동할 때 스크립트를 올린다. 실패해도 builder 는 뜬다 (라우트가 없으면 Worker 를 거치지 않는다) */
    @EventListener(ApplicationReadyEvent.class)
    public void publish() {
        if (!enabled()) {
            return;
        }
        try {
            upload();
        } catch (RuntimeException e) {
            log.warn("edge worker upload failed: {}", e.getMessage());
        }
        if (queue.enabled()) {
            try {
                queueAdmin();
            } catch (RuntimeException e) {
                log.warn("edge write queue admin host failed: {}", e.getMessage());
            }
        }
    }

    /**
     * 쓰기 큐가 켜져 있으면 DO 바인딩을 넣어 올린다. 그게 실패하면 바인딩 없이 올린다 (PC 장애 재시도는 큐 없이도 동작한다).
     * DO 마이그레이션은 Worker 에 없을 때만 보낸다. 있는지 확인하지 못해 보냈다가 거절되면 마이그레이션을 빼고 한 번 더 올린다
     */
    private void upload() {
        Map<String, String> modules = new LinkedHashMap<>();
        modules.put("worker.js", script());
        modules.put(EdgeQueue.MODULE, resource(EdgeQueue.MODULE_RESOURCE));
        if (queue.enabled()) {
            boolean migrated = migrated();
            try {
                uploadWithQueue(modules, migrated);
                return;
            } catch (RuntimeException e) {
                if (!migrated) {
                    try {
                        uploadWithQueue(modules, true);
                        return;
                    } catch (RuntimeException again) {
                        // 아래에서 큐 없이 올린다
                    }
                }
                log.warn("edge worker upload with write queue failed, uploading without it: {}", e.getMessage());
            }
        }
        uploader.upload(settings.accountId(), edge.scriptName(), metadata().toString(), modules);
        log.info("edge worker {} uploaded", edge.scriptName());
    }

    private void uploadWithQueue(Map<String, String> modules, boolean migrated) {
        ObjectNode metadata = metadata();
        queue.bind(metadata, migrated);
        uploader.upload(settings.accountId(), edge.scriptName(), metadata.toString(), modules);
        log.info("edge worker {} uploaded with write queue (migration {})", edge.scriptName(),
                migrated ? "already applied" : EdgeQueue.MIGRATION_TAG);
    }

    private static ObjectNode metadata() {
        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.put("main_module", "worker.js");
        metadata.put("compatibility_date", "2025-09-01");
        return metadata;
    }

    /** Worker 에 쓰기 큐 DO 마이그레이션이 이미 적용됐는가. 확인하지 못하면 false (보내 보고 거절되면 빼고 다시 올린다) */
    private boolean migrated() {
        try {
            JsonNode scripts = api.call("GET", "/accounts/" + settings.accountId() + "/workers/scripts", null);
            if (scripts != null && scripts.isArray()) {
                for (JsonNode script : scripts) {
                    if (edge.scriptName().equals(script.path("id").asText())) {
                        return EdgeQueue.MIGRATION_TAG.equals(script.path("migration_tag").asText());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("edge worker migration tag check failed: {}", e.getMessage());
        }
        return false;
    }

    /** 쓰기 큐 관리 주소. 오리진 없이 Worker 라우트만 쓰므로 프록시 AAAA 100:: 레코드를 둔다 */
    private void queueAdmin() {
        String host = queue.adminHost();
        JsonNode found = api.call("GET", zone() + "/dns_records?name=" + URLEncoder.encode(host, StandardCharsets.UTF_8), null);
        if (found == null || !found.isArray() || found.isEmpty()) {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("type", "AAAA");
            body.put("name", host);
            body.put("content", "100::");
            body.put("proxied", true);
            body.put("ttl", 1);
            api.call("POST", zone() + "/dns_records", body);
        }
        String pattern = host + "/*";
        if (route(pattern) == null) {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("pattern", pattern);
            body.put("script", edge.scriptName());
            api.call("POST", zone() + "/workers/routes", body);
        }
    }

    /** 앱 공개 주소에 Worker 라우트가 있는가 */
    public boolean routed(String app) {
        return route(pattern(app)) != null;
    }

    /** 대기 배포에 넘길 Ingress 별칭. 꺼져 있으면 비어 있다 */
    public List<String> aliases(String app) {
        return enabled() ? List.of(cloudHost(app)) : List.of();
    }

    /**
     * 대기 배포가 끝난 앱. 클라우드 주소를 ALB 로 두고 공개 주소에 Worker 라우트를 건다. 이미 있으면 그대로 둔다
     *
     * @return 화면 로그에 남길 한 줄
     */
    public String attach(String app) {
        return attach(app, origin);
    }

    /** @param cloudOrigin {app}-cloud CNAME 내용물. 비우면 AWS ALB */
    public String attach(String app, String cloudOrigin) {
        String target = cloudOrigin == null || cloudOrigin.isBlank() ? origin : cloudOrigin;
        String cloud = cloudHost(app);
        JsonNode record = record(cloud);
        if (record != null && !target.equals(normalize(record.path("content").asText("")))) {
            throw new IllegalStateException(cloud + " 은 다른 곳을 가리킨다");
        }
        if (record == null || !record.path("proxied").asBoolean(false)) {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("type", "CNAME");
            body.put("name", cloud);
            body.put("content", target);
            body.put("proxied", true);
            body.put("ttl", 1);
            if (record == null) {
                api.call("POST", zone() + "/dns_records", body);
            } else {
                api.call("PUT", zone() + "/dns_records/" + record.path("id").asText(), body);
            }
        }
        String pattern = pattern(app);
        if (route(pattern) == null) {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("pattern", pattern);
            body.put("script", edge.scriptName());
            api.call("POST", zone() + "/workers/routes", body);
        }
        return "edge: " + pattern + " -> " + edge.scriptName() + ", fallback " + cloud;
    }

    /** 앱을 지운 뒤. 라우트와 ALB 를 가리키는 클라우드 주소만 지운다 */
    public void detach(String app) {
        if (queue.enabled()) {
            try {
                queue.clear(app);
            } catch (RuntimeException e) {
                // 남은 큐는 라우트가 없어 쓰이지 않는다. 앱 삭제는 계속한다
                log.warn("edge write queue {} clear failed: {}", app, e.getMessage());
            }
        }
        JsonNode route = route(pattern(app));
        if (route != null) {
            api.call("DELETE", zone() + "/workers/routes/" + route.path("id").asText(), null);
        }
        String cloud = cloudHost(app);
        JsonNode record = record(cloud);
        String content = record == null ? "" : normalize(record.path("content").asText(""));
        if (record != null && (origin.equals(content) || (!extraOrigin.isBlank() && extraOrigin.equals(content)))) {
            api.call("DELETE", zone() + "/dns_records/" + record.path("id").asText(), null);
        }
    }

    String cloudHost(String app) {
        return label(app) + CLOUD_SUFFIX + "." + normalize(settings.zoneName());
    }

    private String pattern(String app) {
        return label(app) + "." + normalize(settings.zoneName()) + "/*";
    }

    private JsonNode route(String pattern) {
        JsonNode routes = api.call("GET", zone() + "/workers/routes", null);
        if (routes == null || !routes.isArray()) {
            return null;
        }
        for (JsonNode route : routes) {
            if (pattern.equals(route.path("pattern").asText())) {
                return route;
            }
        }
        return null;
    }

    private JsonNode record(String host) {
        JsonNode found = api.call("GET", zone() + "/dns_records?name="
                + URLEncoder.encode(host, StandardCharsets.UTF_8), null);
        if (found == null || !found.isArray() || found.isEmpty()) {
            return null;
        }
        if (found.size() > 1 || !"CNAME".equals(found.get(0).path("type").asText())) {
            throw new IllegalStateException(host + " 은 앱 클라우드 주소 레코드가 아니다");
        }
        return found.get(0);
    }

    private String zone() {
        return "/zones/" + settings.zoneId();
    }

    private static String label(String app) {
        String label = app == null ? "" : app.trim().toLowerCase();
        if (!label.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")) {
            throw new IllegalArgumentException("앱 이름이 DNS 이름이 아니다: " + app);
        }
        return label;
    }

    private static String normalize(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase();
        return clean.endsWith(".") ? clean.substring(0, clean.length() - 1) : clean;
    }

    static String script() {
        return resource(SCRIPT_RESOURCE);
    }

    static String resource(String path) {
        try (InputStream in = EdgeWorker.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(path + " 이 없다");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(path + " 을 읽지 못했다", e);
        }
    }

    /** PUT /accounts/{account}/workers/scripts/{name} (ES 모듈 여러 개) */
    static final class HttpUploader implements Uploader {

        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private final String token;

        HttpUploader(String token) {
            this.token = token;
        }

        @Override
        public void upload(String accountId, String scriptName, String metadata, Map<String, String> modules) {
            String boundary = "lily-" + UUID.randomUUID();
            StringBuilder body = new StringBuilder()
                    .append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"metadata\"\r\n")
                    .append("Content-Type: application/json\r\n\r\n").append(metadata).append("\r\n");
            modules.forEach((name, source) -> body
                    .append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(name).append("\"; filename=\"").append(name).append("\"\r\n")
                    .append("Content-Type: application/javascript+module\r\n\r\n").append(source).append("\r\n"));
            body.append("--").append(boundary).append("--\r\n");
            HttpRequest request = HttpRequest.newBuilder(URI.create(AgentCloudflare.API + "/accounts/" + accountId
                            + "/workers/scripts/" + scriptName))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .PUT(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode root = MAPPER.readTree(response.body() == null || response.body().isBlank() ? "{}" : response.body());
                if (!root.path("success").asBoolean(false)) {
                    throw new IllegalStateException(root.path("errors").path(0).path("message")
                            .asText("cloudflare api " + response.statusCode()));
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("worker 업로드가 중단되었다");
            } catch (Exception e) {
                throw new IllegalStateException("worker 업로드 실패: " + e.getMessage());
            }
        }
    }
}
