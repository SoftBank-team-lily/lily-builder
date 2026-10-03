package com.lily.builder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdgeQueueTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final List<String> calls = new ArrayList<>();
    private EdgeQueue.Reply reply = new EdgeQueue.Reply(200, "{\"paths\":[\"/posts\"]}");

    private final EdgeQueue queue = new EdgeQueue("lilycloud.kr.", KEY, (method, url, token, body) -> {
        calls.add(method + " " + url + " " + token + (body == null ? "" : " " + body));
        return reply;
    });

    @Test
    void 키가_base64_32바이트면_켜지고_아니면_꺼진다() {
        assertThat(queue.enabled()).isTrue();
        assertThat(new EdgeQueue("lilycloud.kr", Base64.getEncoder().encodeToString(new byte[16]), null).enabled()).isFalse();
        assertThat(new EdgeQueue("lilycloud.kr", "not base64!", null).enabled()).isFalse();
        assertThat(new EdgeQueue("lilycloud.kr", "", null).enabled()).isFalse();
        assertThat(EdgeQueue.disabled().enabled()).isFalse();
    }

    @Test
    void 관리_토큰은_queue_js와_같이_lily_edge_admin_접두사를_붙인_키의_SHA_256_hex다() throws Exception {
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(("lily-edge-admin:" + KEY).getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(queue.adminToken()).isEqualTo(expected).hasSize(64);
    }

    @Test
    void 등록_경로를_바꾸면_관리_주소의_config에_PUT_하고_토큰을_붙인다() {
        queue.configure("blog", List.of("/posts", "/comments"));

        assertThat(calls).containsExactly("PUT https://lily-edge-queue.lilycloud.kr/apps/blog.lilycloud.kr/queue/config "
                + queue.adminToken() + " {\"paths\":[\"/posts\",\"/comments\"]}");
    }

    @Test
    void 읽기_사본만_끄면_관리_주소의_config에_snapshot_false만_보낸다() {
        queue.configure("blog", null, false);

        assertThat(calls).containsExactly("PUT https://lily-edge-queue.lilycloud.kr/apps/blog.lilycloud.kr/queue/config "
                + queue.adminToken() + " {\"snapshot\":false}");
    }

    @Test
    void 쓰기_큐와_읽기_사본을_켜면_관리_주소의_config에_모든_POST_경로와_snapshot_true를_보낸다() {
        queue.configure("blog", List.of("/"), true);

        assertThat(calls).containsExactly("PUT https://lily-edge-queue.lilycloud.kr/apps/blog.lilycloud.kr/queue/config "
                + queue.adminToken() + " {\"paths\":[\"/\"],\"snapshot\":true}");
    }

    @Test
    void 관리_주소가_400을_주면_IllegalArgumentException() {
        reply = new EdgeQueue.Reply(400, "{\"error\":\"paths\"}");

        assertThatThrownBy(() -> queue.configure("blog", List.of("/posts"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 관리_주소가_401을_주면_IllegalStateException() {
        reply = new EdgeQueue.Reply(401, "{\"error\":\"unauthorized\"}");

        assertThatThrownBy(() -> queue.state("blog")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void DNS_이름이_아닌_앱_이름은_관리_주소를_부르지_않고_IllegalArgumentException() {
        assertThatThrownBy(() -> queue.state("../x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
    }
}
