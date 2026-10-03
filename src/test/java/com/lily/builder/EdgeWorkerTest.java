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
            (account, name, metadata, modules) -> uploads.add(account + "/" + name + ":" + metadata));

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

    private static final String QUEUE_KEY = java.util.Base64.getEncoder().encodeToString(new byte[32]);

    /** 쓰기 큐가 켜진 Worker. failing 이면 그 문자열이 metadata 에 있는 업로드를 거절한다 */
    private EdgeWorker queued(List<String> metadata, List<java.util.Map<String, String>> modules, String failing) {
        return new EdgeWorker(
                new PlatformProperties.Cloudflare("token", "acc", "zone", "lilycloud.kr"),
                new PlatformProperties.Edge(true, "lily-edge"), "alb.example.net",
                (method, path, body) -> {
                    calls.add(method + " " + path + (body == null ? "" : " " + body));
                    if (path.equals("/accounts/acc/workers/scripts")) {
                        return scripts;
                    }
                    if (path.startsWith(Z + "/dns_records?name=")) {
                        return records;
                    }
                    return path.equals(Z + "/workers/routes") && "GET".equals(method) ? routes : json("{}");
                },
                (account, name, meta, mods) -> {
                    if (failing != null && meta.contains(failing)) {
                        throw new IllegalStateException("rejected");
                    }
                    metadata.add(meta);
                    modules.add(mods);
                },
                "", new EdgeQueue("lilycloud.kr", QUEUE_KEY, (method, url, token, body) -> {
                    calls.add("ADMIN " + method + " " + url);
                    return new EdgeQueue.Reply(200, "{}");
                }));
    }

    private JsonNode scripts = JSON.createArrayNode();

    @Test
    void 쓰기_큐를_켜면_DO_바인딩과_큐_키와_관리_주소와_마이그레이션을_넣고_queue_js_모듈을_같이_올린다() {
        List<String> metadata = new ArrayList<>();
        List<java.util.Map<String, String>> modules = new ArrayList<>();

        queued(metadata, modules, null).publish();

        assertThat(metadata).singleElement().asString()
                .contains("\"main_module\":\"worker.js\"")
                .contains("{\"type\":\"durable_object_namespace\",\"name\":\"QUEUE\",\"class_name\":\"WriteQueue\"}")
                .contains("{\"type\":\"secret_text\",\"name\":\"QUEUE_KEY\",\"text\":\"" + QUEUE_KEY + "\"}")
                .contains("{\"type\":\"plain_text\",\"name\":\"QUEUE_ADMIN_HOST\",\"text\":\"lily-edge-queue.lilycloud.kr\"}")
                .contains("\"migrations\":{\"new_tag\":\"lily-queue-v1\",\"new_sqlite_classes\":[\"WriteQueue\"]}");
        assertThat(modules.get(0).keySet()).containsExactly("worker.js", "queue.js");
        assertThat(modules.get(0).get("queue.js")).contains("export class WriteQueue");
    }

    @Test
    void Worker에_큐_마이그레이션이_이미_있으면_마이그레이션_없이_올린다() {
        scripts = json("[{\"id\":\"lily-edge\",\"migration_tag\":\"lily-queue-v1\"}]");
        List<String> metadata = new ArrayList<>();

        queued(metadata, new ArrayList<>(), null).publish();

        assertThat(metadata).singleElement().asString().contains("\"QUEUE\"").doesNotContain("migrations");
    }

    @Test
    void 마이그레이션을_넣은_업로드가_거절되면_마이그레이션을_빼고_다시_올린다() {
        List<String> metadata = new ArrayList<>();

        queued(metadata, new ArrayList<>(), "migrations").publish();

        assertThat(metadata).singleElement().asString().contains("\"QUEUE\"").doesNotContain("migrations");
    }

    @Test
    void 큐_바인딩을_넣은_업로드가_모두_거절되면_바인딩_없이_올린다() {
        List<String> metadata = new ArrayList<>();

        queued(metadata, new ArrayList<>(), "QUEUE").publish();

        assertThat(metadata).singleElement().asString().doesNotContain("bindings").contains("\"main_module\":\"worker.js\"");
    }

    @Test
    void 쓰기_큐를_켜면_관리_주소에_프록시_AAAA_레코드와_라우트를_만든다() {
        queued(new ArrayList<>(), new ArrayList<>(), null).publish();

        assertThat(calls).anyMatch(call -> call.startsWith("POST " + Z + "/dns_records ")
                && call.contains("\"type\":\"AAAA\"")
                && call.contains("\"name\":\"lily-edge-queue.lilycloud.kr\"")
                && call.contains("\"content\":\"100::\"")
                && call.contains("\"proxied\":true"));
        assertThat(calls).anyMatch(call -> call.startsWith("POST " + Z + "/workers/routes ")
                && call.contains("\"pattern\":\"lily-edge-queue.lilycloud.kr/*\""));
    }

    @Test
    void 쓰기_큐가_꺼져_있으면_관리_주소를_만들지_않는다() {
        edge.publish();

        assertThat(calls).noneMatch(call -> call.contains("lily-edge-queue"));
    }

    @Test
    void 앱을_지우면_쓰기_큐_관리_주소로_쌓인_요청을_지운다() {
        queued(new ArrayList<>(), new ArrayList<>(), null).detach("blog");

        assertThat(calls).contains("ADMIN DELETE https://lily-edge-queue.lilycloud.kr/apps/blog.lilycloud.kr/queue");
    }

    @Test
    void 공개_주소_라우트가_있으면_routed는_true() {
        routes = json("[{\"id\":\"w1\",\"pattern\":\"blog.lilycloud.kr/*\",\"script\":\"lily-edge\"}]");

        assertThat(edge.routed("blog")).isTrue();
        assertThat(edge.routed("shop")).isFalse();
    }

    private static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
