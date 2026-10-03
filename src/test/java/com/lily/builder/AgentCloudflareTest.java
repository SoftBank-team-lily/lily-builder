package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentCloudflareTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "a1b2c3d4e5f6";
    private static final String OTHER = "ffffffffffff";
    private static final String TUNNEL = "11111111-2222-3333-4444-555555555555";
    private static final String A = "/accounts/acc";
    private static final String Z = "/zones/zone";

    private final List<String> calls = new ArrayList<>();
    private JsonNode existing = JSON.createArrayNode();
    private JsonNode record;

    private final AgentCloudflare cloudflare = new AgentCloudflare(
            new PlatformProperties.Cloudflare("token", "acc", "zone", "lilycloud.kr"), "alb.example.net",
            (key, app) -> KEY.equals(key) && "blog".equals(app),
            (method, path, body) -> {
                calls.add(method + " " + path + (body == null ? "" : " " + body));
                if (path.startsWith(A + "/cfd_tunnel?")) {
                    return json("[{\"id\":\"" + TUNNEL + "\",\"name\":\"lily-agent-" + KEY + "\"}]");
                }
                if (path.startsWith(Z + "/dns_records?name=")) {
                    return existing;
                }
                if (path.startsWith(Z + "/dns_records/")) {
                    return record;
                }
                return json("{}");
            });

    @Test
    void tunnelIsNamedAfterTheAgentKey() {
        cloudflare.call(KEY, "GET", A + "/cfd_tunnel?is_deleted=false&name=lily-agent-" + KEY, null);

        assertThatThrownBy(() -> cloudflare.call(KEY, "GET", A + "/cfd_tunnel?name=lily-agent-" + OTHER, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cloudflare.call(KEY, "POST", A + "/cfd_tunnel", json("{\"name\":\"lily-edge-1\"}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cloudflare.call(OTHER, "GET", A + "/cfd_tunnel/" + TUNNEL + "/token", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ingressOnlyRoutesOwnedHostsToLoopback() {
        String ok = "{\"config\":{\"ingress\":[{\"hostname\":\"blog.lilycloud.kr\",\"service\":\"http://127.0.0.1:8099\",\"originRequest\":{}},{\"service\":\"http_status:404\"}]}}";
        cloudflare.call(KEY, "PUT", A + "/cfd_tunnel/" + TUNNEL + "/configurations", json(ok));

        assertThatThrownBy(() -> cloudflare.call(KEY, "PUT", A + "/cfd_tunnel/" + TUNNEL + "/configurations",
                json(ok.replace("blog.lilycloud.kr", "builder.lilycloud.kr"))))
                .hasMessageContaining("이 에이전트로 배포한 앱이 아니다");
        assertThatThrownBy(() -> cloudflare.call(KEY, "PUT", A + "/cfd_tunnel/" + TUNNEL + "/configurations",
                json(ok.replace("http://127.0.0.1:8099", "http://10.0.0.5:80"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void homeCutoverMayPointTheRecordAtTheCloudOriginAndBack() {
        String toOrigin = "{\"type\":\"CNAME\",\"name\":\"blog.lilycloud.kr\",\"content\":\"alb.example.net\",\"proxied\":true}";
        String id = "0123456789abcdef0123456789abcdef";
        record = json("{\"name\":\"blog.lilycloud.kr\",\"type\":\"CNAME\",\"content\":\"" + TUNNEL + ".cfargotunnel.com\"}");
        cloudflare.call(KEY, "PUT", Z + "/dns_records/" + id, json(toOrigin));
        assertThat(calls.get(calls.size() - 1)).contains("\"content\":\"alb.example.net\"");

        record = json("{\"name\":\"blog.lilycloud.kr\",\"type\":\"CNAME\",\"content\":\"alb.example.net\"}");
        cloudflare.call(KEY, "PUT", Z + "/dns_records/" + id,
                json(toOrigin.replace("alb.example.net", TUNNEL + ".cfargotunnel.com")));
        assertThat(calls.get(calls.size() - 1)).contains(TUNNEL + ".cfargotunnel.com");

        // 오리진은 PUT 으로만. 새 레코드는 터널로만 만든다
        assertThatThrownBy(() -> cloudflare.call(KEY, "POST", Z + "/dns_records", json(toOrigin)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cloudflare.call(KEY, "PUT", Z + "/dns_records/" + id,
                json(toOrigin.replace("alb.example.net", "evil.example.com"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void GCP_앱의_거점_전환은_ALB_가_아니라_GCP_로드밸런서를_가리킨다() {
        AgentCloudflare withGcp = new AgentCloudflare(
                new PlatformProperties.Cloudflare("token", "acc", "zone", "lilycloud.kr"), "alb.example.net",
                (key, app) -> KEY.equals(key) && "blog".equals(app),
                (method, path, body) -> {
                    calls.add(method + " " + path + (body == null ? "" : " " + body));
                    return path.startsWith(Z + "/dns_records/") ? record : json("{}");
                }, "gcp.lilycloud.kr");
        String id = "0123456789abcdef0123456789abcdef";
        record = json("{\"name\":\"blog.lilycloud.kr\",\"type\":\"CNAME\",\"content\":\"" + TUNNEL + ".cfargotunnel.com\"}");

        withGcp.call(KEY, "PUT", Z + "/dns_records/" + id,
                json("{\"type\":\"CNAME\",\"name\":\"blog.lilycloud.kr\",\"content\":\"GCP.lilycloud.kr.\",\"proxied\":true}"));
        assertThat(calls.get(calls.size() - 1)).contains("\"content\":\"gcp.lilycloud.kr\"").doesNotContain("alb.example.net");

        withGcp.call(KEY, "PUT", Z + "/dns_records/" + id,
                json("{\"type\":\"CNAME\",\"name\":\"blog.lilycloud.kr\",\"content\":\"alb.example.net\",\"proxied\":true}"));
        assertThat(calls.get(calls.size() - 1)).contains("\"content\":\"alb.example.net\"");
    }

    @Test
    void dnsOnlyPointsOwnedHostsAtTheAgentTunnel() {
        String body = "{\"type\":\"CNAME\",\"name\":\"blog.lilycloud.kr\",\"content\":\"" + TUNNEL + ".cfargotunnel.com\",\"proxied\":true}";
        cloudflare.call(KEY, "POST", Z + "/dns_records", json(body));

        assertThatThrownBy(() -> cloudflare.call(KEY, "POST", Z + "/dns_records",
                json(body.replace(TUNNEL + ".cfargotunnel.com", "evil.example.com"))))
                .isInstanceOf(IllegalArgumentException.class);

        existing = json("[{\"name\":\"blog.lilycloud.kr\",\"type\":\"A\",\"content\":\"1.2.3.4\"}]");
        assertThatThrownBy(() -> cloudflare.call(KEY, "POST", Z + "/dns_records", json(body)))
                .hasMessageContaining("이미 다른 레코드");

        String rid = "0123456789abcdef0123456789abcdef";
        record = json("{\"name\":\"blog.lilycloud.kr\",\"type\":\"CNAME\",\"content\":\"abc.elb.amazonaws.com\"}");
        assertThatThrownBy(() -> cloudflare.call(KEY, "PUT", Z + "/dns_records/" + rid, json(body)))
                .hasMessageContaining("에이전트 터널 레코드가 아니다");

        record = json("{\"name\":\"blog.lilycloud.kr\",\"type\":\"CNAME\",\"content\":\"old.cfargotunnel.com\"}");
        cloudflare.call(KEY, "PUT", Z + "/dns_records/" + rid, json(body));
        assertThat(calls.getLast()).startsWith("PUT " + Z + "/dns_records/" + rid);
    }

    @Test
    void removeHostnameDropsOnlyThatHostRuleAndKeepsTheRest() {
        List<String> puts = new ArrayList<>();
        AgentCloudflare withConfig = new AgentCloudflare(
                new PlatformProperties.Cloudflare("token", "acc", "zone", "lilycloud.kr"), "alb.example.net",
                (key, app) -> true,
                (method, path, body) -> {
                    if (path.startsWith(A + "/cfd_tunnel?")) {
                        return json("[{\"id\":\"" + TUNNEL + "\",\"name\":\"lily-agent-" + KEY + "\"}]");
                    }
                    if ("GET".equals(method) && path.endsWith("/configurations")) {
                        return json("{\"config\":{\"ingress\":[{\"hostname\":\"blog.lilycloud.kr\",\"service\":\"http://127.0.0.1:8099\"},"
                                + "{\"hostname\":\"shop.lilycloud.kr\",\"service\":\"http://127.0.0.1:8099\"},"
                                + "{\"service\":\"http_status:404\"}]}}");
                    }
                    if ("PUT".equals(method)) {
                        puts.add(path + " " + body);
                    }
                    return json("{}");
                });

        assertThat(withConfig.removeHostname(KEY, "blog.lilycloud.kr")).isTrue();
        assertThat(puts).hasSize(1);
        assertThat(puts.get(0)).startsWith(A + "/cfd_tunnel/" + TUNNEL + "/configurations ")
                .doesNotContain("blog.lilycloud.kr").contains("shop.lilycloud.kr").contains("http_status:404");

        puts.clear();
        assertThat(withConfig.removeHostname(KEY, "none.lilycloud.kr")).isFalse();
        assertThat(puts).isEmpty();
    }

    @Test
    void otherCallsAreRefused() {
        assertThatThrownBy(() -> cloudflare.call(KEY, "DELETE", Z + "/dns_records/0123456789abcdef0123456789abcdef", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cloudflare.call(KEY, "GET", "/zones/another/dns_records?name=blog.lilycloud.kr", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cloudflare.call(KEY, "GET", Z + "/dns_records?type=CNAME&name=www.lilycloud.kr", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
