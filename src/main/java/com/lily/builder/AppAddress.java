package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 앱 공개 주소 {@code {app}.{플랫폼 존}} 의 Cloudflare 프록시 CNAME. 내용물이 곧 거점이다.
 *
 * <pre>
 * 클라우드  {app}.{zone} → ALB(cloudOrigin) → ingress-nginx → Pod
 * 내 PC    {app}.{zone} → {tunnel}.cfargotunnel.com → 에이전트 프록시 → 컨테이너
 * </pre>
 *
 * 클라우드 배포가 끝나면 레코드를 ALB 로 둔다 ({@link #ensureCloud}). 내 PC 로 옮길 때는 에이전트가 같은 레코드를
 * 터널로 바꾸고 ({@link AgentCloudflare}), 옮기다 실패하면 여기서 ALB 로 되돌린다 ({@link #pointCloud}).
 * 터널도 ALB 도 아닌 레코드(apex, www 등)는 건드리지 않는다.
 */
@Component
public class AppAddress {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?");

    /** CLOUD: ALB, ONPREM: 에이전트 터널, NONE: 레코드 없음, OTHER: 앱 레코드가 아니다 */
    public enum Home { CLOUD, ONPREM, NONE, OTHER }

    /** @param content CNAME 내용물. 레코드가 없으면 빈 문자열 */
    public record State(String host, Home home, String content) {
    }

    private final PlatformProperties.Cloudflare settings;
    private final String origin;
    private final AgentCloudflare.Api api;

    @Autowired
    public AppAddress(PlatformProperties props) {
        this(props.cloudflare(), props.burst().origin(), new AgentCloudflare.HttpApi(props.cloudflare().apiToken()));
    }

    AppAddress(PlatformProperties.Cloudflare settings, String origin, AgentCloudflare.Api api) {
        this.settings = settings;
        this.origin = origin == null ? "" : origin;
        this.api = api;
    }

    /** 플랫폼 존이나 ALB 가 없으면 주소를 만들지 않는다 (cicd 의 Ingress 호스트만 남는다) */
    static AppAddress disabled() {
        return new AppAddress(new PlatformProperties.Cloudflare("", "", "", ""), "", (method, path, body) -> {
            throw new IllegalStateException("플랫폼 존이 설정되지 않았다");
        });
    }

    public boolean enabled() {
        return settings.configured() && !origin.isBlank();
    }

    public State state(String app) {
        String host = host(app);
        return stateOf(host, record(host));
    }

    private State stateOf(String host, JsonNode record) {
        if (record == null) {
            return new State(host, Home.NONE, "");
        }
        String content = normalize(record.path("content").asText(""));
        Home home = !"CNAME".equals(record.path("type").asText()) ? Home.OTHER
                : content.equals(origin) ? Home.CLOUD
                : content.endsWith(".cfargotunnel.com") ? Home.ONPREM
                : Home.OTHER;
        return new State(host, home, content);
    }

    /**
     * 클라우드 배포가 끝난 뒤. 레코드가 없으면 ALB 로 만들고, ALB 면 프록시만 맞춘다.
     * 내 PC 터널이거나 다른 레코드면 그대로 두고 그 상태를 돌려준다 (같은 이름의 온프레미스 앱 주소를 뺏지 않는다)
     */
    public State ensureCloud(String app) {
        State state = state(app);
        if (state.home() == Home.NONE || state.home() == Home.CLOUD) {
            return pointCloud(app);
        }
        return state;
    }

    /**
     * 주소를 ALB 로 둔다. 내 PC 터널을 가리키면 바꾸고, 없으면 만든다 (내 PC 로 옮기다 실패했을 때).
     *
     * @throws IllegalStateException 앱 레코드가 아니다
     */
    public State pointCloud(String app) {
        String host = host(app);
        JsonNode record = record(host);
        State state = stateOf(host, record);
        if (state.home() == Home.OTHER) {
            throw new IllegalStateException(host + " 은 앱 주소 레코드가 아니다");
        }
        if (state.home() == Home.CLOUD && record.path("proxied").asBoolean(false)) {
            return state;
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("type", "CNAME");
        body.put("name", host);
        body.put("content", origin);
        body.put("proxied", true);
        body.put("ttl", 1);
        String zone = "/zones/" + settings.zoneId();
        if (record == null) {
            api.call("POST", zone + "/dns_records", body);
        } else {
            api.call("PUT", zone + "/dns_records/" + record.path("id").asText(), body);
        }
        return new State(host, Home.CLOUD, origin);
    }

    /** 클라우드 앱을 지운 뒤. ALB 를 가리키는 레코드만 지운다 */
    public boolean removeCloud(String app) {
        String host = host(app);
        JsonNode record = record(host);
        if (stateOf(host, record).home() != Home.CLOUD) {
            return false;
        }
        api.call("DELETE", "/zones/" + settings.zoneId() + "/dns_records/" + record.path("id").asText(), null);
        return true;
    }

    /** 온프레미스 앱을 지운 뒤. 앱 레코드면 ALB 든 내 PC 터널이든 지운다. 앱 레코드가 아니면 그대로 두고 false */
    public boolean remove(String app) {
        String host = host(app);
        JsonNode record = record(host);
        Home home = stateOf(host, record).home();
        if (home != Home.CLOUD && home != Home.ONPREM) {
            return false;
        }
        api.call("DELETE", "/zones/" + settings.zoneId() + "/dns_records/" + record.path("id").asText(), null);
        return true;
    }

    public String host(String app) {
        String label = app == null ? "" : app.trim().toLowerCase();
        if (!LABEL.matcher(label).matches()) {
            throw new IllegalArgumentException("앱 이름이 DNS 이름이 아니다: " + app);
        }
        return label + "." + normalize(settings.zoneName());
    }

    /** 이름이 같은 레코드. 둘 이상이면 앱 레코드가 아니므로 타입을 비워 OTHER 로 본다 */
    private JsonNode record(String host) {
        JsonNode found = api.call("GET", "/zones/" + settings.zoneId() + "/dns_records?name="
                + URLEncoder.encode(host, StandardCharsets.UTF_8), null);
        if (found == null || !found.isArray() || found.isEmpty()) {
            return null;
        }
        if (found.size() > 1) {
            ObjectNode many = MAPPER.createObjectNode();
            many.put("type", "MULTIPLE");
            return many;
        }
        return found.get(0);
    }

    private static String normalize(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase();
        return clean.endsWith(".") ? clean.substring(0, clean.length() - 1) : clean;
    }
}
