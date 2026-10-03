package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 에이전트(lily-on-premise CloudflareHostnameProvisioner)의 Cloudflare API 호출을 플랫폼 토큰으로 대신 한다.
 * 에이전트가 보내는 경로를 그대로 넘기지 않는다. 아래 호출만 알아보고, 경로와 본문은 여기서 다시 만든다.
 *
 * <pre>
 * 터널   GET  /accounts/{a}/cfd_tunnel?is_deleted=false&name=lily-agent-{key}
 *        POST /accounts/{a}/cfd_tunnel                       name 은 lily-agent-{key}
 *        GET  /accounts/{a}/cfd_tunnel/{id}/token            id 는 lily-agent-{key} 터널
 *        PUT  /accounts/{a}/cfd_tunnel/{id}/configurations   hostname 은 이 에이전트의 앱, service 는 http://127.0.0.1:{port}
 * DNS    GET  /zones/{z}/dns_records?type=CNAME&name={app}.{zone}
 *        POST /zones/{z}/dns_records                         같은 이름의 레코드가 없을 때만, 내용물은 이 에이전트의 터널
 *        PUT  /zones/{z}/dns_records/{rid}                   기존 레코드가 같은 이름이고 터널(cfargotunnel.com)이나 클라우드 오리진을
 *                                                            가리킬 때만. 내용물은 이 에이전트의 터널 또는 클라우드 오리진 (거점 전환)
 * 인증서 GET  /zones/{z}/ssl/certificate_packs
 *        POST /zones/{z}/ssl/certificate_packs/order         hosts 는 이 에이전트의 앱 호스트 하나
 * </pre>
 *
 * 앱은 이 에이전트 key 로 가장 최근에 배포를 보낸 앱이다 ({@link AgentDeployService#ownedBy}).
 */
@Component
public class AgentCloudflare {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TUNNEL_ID = Pattern.compile("[0-9a-f-]{36}");
    private static final Pattern RECORD_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern LOOPBACK = Pattern.compile("http://127\\.0\\.0\\.1:\\d{1,5}");
    static final String API = "https://api.cloudflare.com/client/v4";

    private final PlatformProperties.Cloudflare settings;
    /** 거점이 클라우드일 때의 CNAME 내용물. 비우면 터널로만 가리킨다 */
    private final String cloudOrigin;
    private final BiPredicate<String, String> owns;
    private final Api api;
    private final Map<String, String> tunnelIds = new ConcurrentHashMap<>();

    @Autowired
    public AgentCloudflare(PlatformProperties props, AgentDeployService deploys) {
        this(props.cloudflare(), props.burst().origin(), deploys::ownedBy, new HttpApi(props.cloudflare().apiToken()));
    }

    AgentCloudflare(PlatformProperties.Cloudflare settings, BiPredicate<String, String> owns, Api api) {
        this(settings, "", owns, api);
    }

    AgentCloudflare(PlatformProperties.Cloudflare settings, String cloudOrigin, BiPredicate<String, String> owns,
                    Api api) {
        this.settings = settings;
        this.cloudOrigin = cloudOrigin == null ? "" : cloudOrigin;
        this.owns = owns;
        this.api = api;
    }

    public boolean enabled() {
        return settings.configured();
    }

    /** welcome 에 싣는 존 정보. 토큰은 싣지 않는다 */
    public Map<String, String> zone() {
        Map<String, String> zone = new LinkedHashMap<>();
        zone.put("accountId", settings.accountId());
        zone.put("zoneId", settings.zoneId());
        zone.put("zoneName", zoneName());
        return zone;
    }

    /** 에이전트가 터널 이름에 쓰는 id. 터널 이름은 lily-{이 값} */
    public static String tunnelAgentId(String key) {
        return "agent-" + key;
    }

    /**
     * @return Cloudflare 응답의 result
     * @throws IllegalArgumentException 허용하지 않는 호출
     */
    public JsonNode call(String key, String method, String rawPath, JsonNode body) {
        if (!enabled()) {
            throw new IllegalArgumentException("플랫폼 존이 설정되지 않았다");
        }
        if (!AgentTokens.KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("에이전트 key 가 아니다");
        }
        String path = rawPath == null ? "" : rawPath;
        int q = path.indexOf('?');
        String route = q < 0 ? path : path.substring(0, q);
        Map<String, String> query = query(q < 0 ? "" : path.substring(q + 1));
        String account = "/accounts/" + settings.accountId();
        String zone = "/zones/" + settings.zoneId();

        if (route.equals(account + "/cfd_tunnel")) {
            if ("GET".equals(method)) {
                require(tunnelName(key).equals(query.get("name")), "다른 터널을 찾을 수 없다");
                return api.call("GET", account + "/cfd_tunnel?is_deleted=false&name=" + encode(tunnelName(key)), null);
            }
            if ("POST".equals(method)) {
                require(body != null && tunnelName(key).equals(body.path("name").asText()), "터널 이름은 " + tunnelName(key));
                ObjectNode create = MAPPER.createObjectNode();
                create.put("name", tunnelName(key));
                create.put("config_src", "cloudflare");
                JsonNode created = api.call("POST", account + "/cfd_tunnel", create);
                tunnelIds.put(key, created.path("id").asText(""));
                return created;
            }
        }
        Matcher tunnel = Pattern.compile(Pattern.quote(account) + "/cfd_tunnel/([^/]+)/(token|configurations)").matcher(route);
        if (tunnel.matches()) {
            String id = tunnel.group(1);
            require(TUNNEL_ID.matcher(id).matches() && id.equals(tunnelId(key)), "이 에이전트의 터널이 아니다");
            if ("token".equals(tunnel.group(2)) && "GET".equals(method)) {
                return api.call("GET", account + "/cfd_tunnel/" + id + "/token", null);
            }
            if ("configurations".equals(tunnel.group(2)) && "PUT".equals(method)) {
                return api.call("PUT", account + "/cfd_tunnel/" + id + "/configurations", ingress(key, body));
            }
        }
        if (route.equals(zone + "/dns_records")) {
            if ("GET".equals(method)) {
                String host = ownedHost(key, query.get("name"));
                return api.call("GET", zone + "/dns_records?type=CNAME&name=" + encode(host), null);
            }
            if ("POST".equals(method)) {
                String host = ownedHost(key, body == null ? null : body.path("name").asText());
                JsonNode existing = api.call("GET", zone + "/dns_records?name=" + encode(host), null);
                require(existing == null || existing.isEmpty(), host + " 은 이미 다른 레코드가 있다");
                return api.call("POST", zone + "/dns_records", cname(key, host, body, false));
            }
        }
        Matcher record = Pattern.compile(Pattern.quote(zone) + "/dns_records/([^/]+)").matcher(route);
        if (record.matches() && "PUT".equals(method)) {
            String id = record.group(1);
            require(RECORD_ID.matcher(id).matches(), "레코드 id 가 아니다");
            String host = ownedHost(key, body == null ? null : body.path("name").asText());
            JsonNode current = api.call("GET", zone + "/dns_records/" + id, null);
            String content = current == null ? "" : current.path("content").asText();
            require(current != null && host.equalsIgnoreCase(current.path("name").asText())
                    && "CNAME".equals(current.path("type").asText())
                    && (content.endsWith(".cfargotunnel.com") || isOrigin(content)),
                    host + " 은 에이전트 터널 레코드가 아니다");
            return api.call("PUT", zone + "/dns_records/" + id, cname(key, host, body, true));
        }
        if (route.equals(zone + "/ssl/certificate_packs") && "GET".equals(method)) {
            return api.call("GET", zone + "/ssl/certificate_packs", null);
        }
        if (route.equals(zone + "/ssl/certificate_packs/order") && "POST".equals(method)) {
            JsonNode hosts = body == null ? null : body.path("hosts");
            require(hosts != null && hosts.isArray() && hosts.size() == 1, "인증서는 호스트 하나만 주문한다");
            String host = ownedHost(key, hosts.get(0).asText());
            ObjectNode order = MAPPER.createObjectNode();
            order.put("type", "advanced");
            order.putArray("hosts").add(host);
            order.put("validation_method", "txt");
            order.put("validity_days", 90);
            order.put("certificate_authority", "google");
            return api.call("POST", zone + "/ssl/certificate_packs/order", order);
        }
        throw new IllegalArgumentException("허용하지 않는 Cloudflare 호출: " + method + " " + route);
    }

    /**
     * 앱을 지운 뒤. 이 에이전트 터널의 ingress 에서 그 호스트 규칙만 뺀다 (다른 규칙과 마지막 404 는 그대로).
     * 에이전트를 거치지 않고 플랫폼 토큰으로 직접 부른다 (에이전트가 꺼져 있어도 지운다)
     *
     * @return 뺀 규칙이 있으면 true
     */
    public boolean removeHostname(String key, String host) {
        if (!enabled() || !AgentTokens.KEY.matcher(key).matches()) {
            return false;
        }
        String id = tunnelId(key);
        if (id.isBlank()) {
            return false;
        }
        String path = "/accounts/" + settings.accountId() + "/cfd_tunnel/" + id + "/configurations";
        JsonNode current = api.call("GET", path, null);
        JsonNode config = current == null ? null : current.path("config");
        JsonNode rules = config == null ? null : config.path("ingress");
        if (rules == null || !rules.isArray()) {
            return false;
        }
        ObjectNode next = config.deepCopy();
        var out = next.putArray("ingress");
        boolean removed = false;
        for (JsonNode rule : rules) {
            if (host.equalsIgnoreCase(rule.path("hostname").asText())) {
                removed = true;
                continue;
            }
            out.add(rule);
        }
        if (!removed) {
            return false;
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.set("config", next);
        api.call("PUT", path, body);
        return true;
    }

    static String tunnelName(String key) {
        return "lily-" + tunnelAgentId(key);
    }

    private String tunnelId(String key) {
        String cached = tunnelIds.get(key);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        JsonNode found = api.call("GET", "/accounts/" + settings.accountId()
                + "/cfd_tunnel?is_deleted=false&name=" + encode(tunnelName(key)), null);
        if (found != null && found.isArray()) {
            for (JsonNode tunnel : found) {
                if (tunnelName(key).equals(tunnel.path("name").asText())) {
                    String id = tunnel.path("id").asText("");
                    tunnelIds.put(key, id);
                    return id;
                }
            }
        }
        return "";
    }

    /** 이 에이전트로 배포한 앱의 {app}.{zone} */
    private String ownedHost(String key, String host) {
        String suffix = "." + zoneName();
        String value = host == null ? "" : host.trim().toLowerCase();
        require(value.endsWith(suffix), "플랫폼 존의 호스트가 아니다");
        String app = value.substring(0, value.length() - suffix.length());
        require(AgentDeployService.APP_NAME.matcher(app).matches(), "앱 호스트가 아니다");
        require(owns.test(key, app), app + " 은 이 에이전트로 배포한 앱이 아니다");
        return value;
    }

    private ObjectNode ingress(String key, JsonNode body) {
        JsonNode rules = body == null ? null : body.path("config").path("ingress");
        require(rules != null && rules.isArray() && rules.size() >= 1, "ingress 가 없다");
        ObjectNode config = MAPPER.createObjectNode();
        var out = config.putObject("config").putArray("ingress");
        for (int i = 0; i < rules.size(); i++) {
            JsonNode rule = rules.get(i);
            boolean last = i == rules.size() - 1;
            if (last) {
                require(!rule.has("hostname") && "http_status:404".equals(rule.path("service").asText()),
                        "마지막 규칙은 http_status:404");
                out.addObject().put("service", "http_status:404");
                continue;
            }
            String service = rule.path("service").asText();
            require(LOOPBACK.matcher(service).matches(), "service 는 http://127.0.0.1:{port}");
            ObjectNode clean = out.addObject();
            clean.put("hostname", ownedHost(key, rule.path("hostname").asText()));
            clean.put("service", service);
            clean.putObject("originRequest");
        }
        return config;
    }

    /** @param origin 거점 전환으로 클라우드 오리진을 가리켜도 된다 */
    private ObjectNode cname(String key, String host, JsonNode body, boolean origin) {
        String wanted = body == null ? "" : body.path("content").asText();
        String target;
        if (origin && isOrigin(wanted)) {
            target = cloudOrigin;
        } else {
            target = tunnelId(key) + ".cfargotunnel.com";
            require(!tunnelId(key).isBlank(), "이 에이전트의 터널이 아직 없다");
            require(target.equals(wanted), "내용물은 이 에이전트의 터널이어야 한다");
        }
        ObjectNode clean = MAPPER.createObjectNode();
        clean.put("type", "CNAME");
        clean.put("name", host);
        clean.put("content", target);
        clean.put("proxied", true);
        clean.put("ttl", 1);
        return clean;
    }

    private boolean isOrigin(String content) {
        String value = content == null ? "" : content.trim().toLowerCase();
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return !cloudOrigin.isBlank() && cloudOrigin.equals(value);
    }

    private String zoneName() {
        String zone = settings.zoneName().trim().toLowerCase();
        return zone.endsWith(".") ? zone.substring(0, zone.length() - 1) : zone;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            values.put(name, value);
        }
        return values;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /** Cloudflare API v4. 성공 응답의 result 만 돌려준다 */
    interface Api {
        JsonNode call(String method, String path, JsonNode body);
    }

    static final class HttpApi implements Api {

        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private final String token;

        HttpApi(String token) {
            this.token = token;
        }

        @Override
        public JsonNode call(String method, String path, JsonNode body) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(API + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json");
                request.method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
                HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                JsonNode root = MAPPER.readTree(response.body() == null || response.body().isBlank() ? "{}" : response.body());
                if (!root.path("success").asBoolean(false)) {
                    throw new IllegalStateException(root.path("errors").path(0).path("message")
                            .asText("cloudflare api " + response.statusCode()));
                }
                return root.get("result");
            } catch (IllegalStateException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("cloudflare api 가 중단되었다");
            } catch (Exception e) {
                throw new IllegalStateException("cloudflare api 호출 실패: " + e.getMessage());
            }
        }
    }
}
