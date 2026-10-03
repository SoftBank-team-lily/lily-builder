package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppAddressTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String Z = "/zones/zone";
    private static final String TUNNEL = "11111111-2222-3333-4444-555555555555.cfargotunnel.com";

    private final List<String> calls = new ArrayList<>();
    private JsonNode existing = JSON.createArrayNode();

    private final AppAddress addresses = new AppAddress(
            new PlatformProperties.Cloudflare("token", "acc", "zone", "lilycloud.kr"), "alb.example.net",
            (method, path, body) -> {
                calls.add(method + " " + path + (body == null ? "" : " " + body));
                return path.startsWith(Z + "/dns_records?name=") ? existing : json("{}");
            });

    @Test
    void 클라우드_배포는_주소가_없으면_ALB_프록시_CNAME_을_만든다() {
        AppAddress.State state = addresses.ensureCloud("blog");

        assertThat(state.home()).isEqualTo(AppAddress.Home.CLOUD);
        assertThat(calls).anyMatch(call -> call.startsWith("POST " + Z + "/dns_records ")
                && call.contains("\"name\":\"blog.lilycloud.kr\"")
                && call.contains("\"content\":\"alb.example.net\"")
                && call.contains("\"proxied\":true"));
    }

    @Test
    void 이미_ALB_를_가리키면_다시_쓰지_않는다() {
        existing = record("CNAME", "alb.example.net", true);

        addresses.ensureCloud("blog");

        assertThat(calls).noneMatch(call -> call.startsWith("POST") || call.startsWith("PUT"));
    }

    @Test
    void 클라우드_배포는_내_PC_터널을_가리키는_주소를_뺏지_않는다() {
        existing = record("CNAME", TUNNEL, true);

        AppAddress.State state = addresses.ensureCloud("blog");

        assertThat(state.home()).isEqualTo(AppAddress.Home.ONPREM);
        assertThat(calls).noneMatch(call -> call.startsWith("POST") || call.startsWith("PUT"));
    }

    @Test
    void 옮기기가_실패하면_터널을_ALB_로_되돌린다() {
        existing = record("CNAME", TUNNEL, true);

        AppAddress.State state = addresses.pointCloud("blog");

        assertThat(state.home()).isEqualTo(AppAddress.Home.CLOUD);
        assertThat(calls).anyMatch(call -> call.startsWith("PUT " + Z + "/dns_records/rec1 ")
                && call.contains("\"content\":\"alb.example.net\""));
    }

    @Test
    void 앱_레코드가_아니면_건드리지_않는다() {
        existing = record("A", "75.2.85.42", true);

        assertThat(addresses.ensureCloud("www").home()).isEqualTo(AppAddress.Home.OTHER);
        assertThatThrownBy(() -> addresses.pointCloud("www")).isInstanceOf(IllegalStateException.class);
        assertThat(addresses.removeCloud("www")).isFalse();
        assertThat(calls).allMatch(call -> call.startsWith("GET"));
    }

    @Test
    void 앱을_지우면_ALB_를_가리키는_주소만_지운다() {
        existing = record("CNAME", "alb.example.net", true);
        assertThat(addresses.removeCloud("blog")).isTrue();
        assertThat(calls).contains("DELETE " + Z + "/dns_records/rec1");

        calls.clear();
        existing = record("CNAME", TUNNEL, true);
        assertThat(addresses.removeCloud("blog")).isFalse();
        assertThat(calls).noneMatch(call -> call.startsWith("DELETE"));
    }

    @Test
    void 온프레미스_앱을_지우면_내_PC_터널을_가리키는_주소도_지운다() {
        existing = record("CNAME", TUNNEL, true);

        assertThat(addresses.remove("blog")).isTrue();

        assertThat(calls).contains("DELETE " + Z + "/dns_records/rec1");
    }

    @Test
    void 온프레미스_앱을_지워도_앱_레코드가_아니면_지우지_않는다() {
        existing = record("A", "75.2.85.42", true);

        assertThat(addresses.remove("www")).isFalse();

        assertThat(calls).noneMatch(call -> call.startsWith("DELETE"));
    }

    @Test
    void 존이나_ALB_가_없으면_꺼진다() {
        assertThat(AppAddress.disabled().enabled()).isFalse();
        assertThat(new AppAddress(new PlatformProperties.Cloudflare("token", "acc", "zone", "lilycloud.kr"), "",
                (method, path, body) -> null).enabled()).isFalse();
    }

    private static JsonNode record(String type, String content, boolean proxied) {
        return json("[{\"id\":\"rec1\",\"type\":\"" + type + "\",\"name\":\"blog.lilycloud.kr\",\"content\":\""
                + content + "\",\"proxied\":" + proxied + "}]");
    }

    private static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
