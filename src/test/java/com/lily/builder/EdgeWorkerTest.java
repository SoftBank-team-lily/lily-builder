package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdgeWorkerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String Z = "/zones/zone";

    private final List<String> calls = new ArrayList<>();
    private final List<String> uploads = new ArrayList<>();
    private JsonNode records = JSON.createArrayNode();
    private JsonNode routes = JSON.createArrayNode();

    private final EdgeWorker edge = new EdgeWorker(
            new PlatformProperties.Cloudflare("token", "acc", "zone", "lilycloud.kr"),
            new PlatformProperties.Edge(true, "lily-edge"), "alb.example.net",
            (method, path, body) -> {
                calls.add(method + " " + path + (body == null ? "" : " " + body));
                if (path.startsWith(Z + "/dns_records?name=")) {
                    return records;
                }
                return path.equals(Z + "/workers/routes") && "GET".equals(method) ? routes : json("{}");
            },
            (account, name, source) -> uploads.add(account + "/" + name + ":" + source.length()));

    @Test
    void 대기_배포_앱에_클라우드_주소와_라우트를_만든다() {
        String line = edge.attach("blog");

        assertThat(calls).anyMatch(call -> call.startsWith("POST " + Z + "/dns_records ")
                && call.contains("\"name\":\"blog-cloud.lilycloud.kr\"")
                && call.contains("\"content\":\"alb.example.net\"")
                && call.contains("\"proxied\":true"));
        assertThat(calls).anyMatch(call -> call.startsWith("POST " + Z + "/workers/routes ")
                && call.contains("\"pattern\":\"blog.lilycloud.kr/*\"")
                && call.contains("\"script\":\"lily-edge\""));
        assertThat(line).contains("blog.lilycloud.kr/*").contains("blog-cloud.lilycloud.kr");
    }

    @Test
    void 이미_있으면_다시_만들지_않는다() {
        records = json("[{\"id\":\"r1\",\"type\":\"CNAME\",\"content\":\"alb.example.net\",\"proxied\":true}]");
        routes = json("[{\"id\":\"w1\",\"pattern\":\"blog.lilycloud.kr/*\",\"script\":\"lily-edge\"}]");

        edge.attach("blog");

        assertThat(calls).noneMatch(call -> call.startsWith("POST") || call.startsWith("PUT"));
    }

    @Test
    void 다른_곳을_가리키는_클라우드_주소는_건드리지_않는다() {
        records = json("[{\"id\":\"r1\",\"type\":\"CNAME\",\"content\":\"elsewhere.example.net\",\"proxied\":true}]");

        assertThatThrownBy(() -> edge.attach("blog")).isInstanceOf(IllegalStateException.class);
        assertThat(calls).noneMatch(call -> call.startsWith("POST") || call.startsWith("PUT"));
    }

    @Test
    void 앱을_지우면_라우트와_ALB_를_가리키는_클라우드_주소를_지운다() {
        records = json("[{\"id\":\"r1\",\"type\":\"CNAME\",\"content\":\"alb.example.net\",\"proxied\":true}]");
        routes = json("[{\"id\":\"w1\",\"pattern\":\"blog.lilycloud.kr/*\",\"script\":\"lily-edge\"}]");

        edge.detach("blog");

        assertThat(calls).contains("DELETE " + Z + "/workers/routes/w1", "DELETE " + Z + "/dns_records/r1");
    }

    @Test
    void 대기_배포에는_클라우드_주소를_Ingress_별칭으로_넘긴다() {
        assertThat(edge.aliases("blog")).containsExactly("blog-cloud.lilycloud.kr");
        assertThat(EdgeWorker.disabled().aliases("blog")).isEmpty();
    }

    @Test
    void 온프레미스_배포_앱에는_공개_주소_라우트만_걸고_클라우드_주소_레코드는_만들지_않는다() {
        String line = edge.attachRoute("blog");

        assertThat(calls).anyMatch(call -> call.startsWith("POST " + Z + "/workers/routes ")
                && call.contains("\"pattern\":\"blog.lilycloud.kr/*\"")
                && call.contains("\"script\":\"lily-edge\""));
        assertThat(calls).noneMatch(call -> call.contains("/dns_records"));
        assertThat(line).isEqualTo("edge: blog.lilycloud.kr/* -> lily-edge");
    }

    @Test
    void 온프레미스_배포_앱에_라우트가_이미_있으면_다시_만들지_않는다() {
        routes = json("[{\"id\":\"w1\",\"pattern\":\"blog.lilycloud.kr/*\",\"script\":\"lily-edge\"}]");

        edge.attachRoute("blog");

        assertThat(calls).noneMatch(call -> call.startsWith("POST"));
    }

    @Test
    void 공개_주소는_앱_이름과_존으로_만든다() {
        assertThat(edge.publicUrl("Blog")).isEqualTo("https://blog.lilycloud.kr/");
    }

    @Test
    void 기동할_때_스크립트를_올린다() {
        edge.publish();

        assertThat(uploads).singleElement().asString().startsWith("acc/lily-edge:");
        assertThat(EdgeWorker.script()).contains("export default").contains("530");
    }

    private static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
