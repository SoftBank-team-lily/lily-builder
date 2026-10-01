package com.lily.builder;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class AiAdvisorTest {

    private MockWebServer openai;

    @BeforeEach
    void start() throws IOException {
        openai = new MockWebServer();
        openai.start();
    }

    @AfterEach
    void stop() throws IOException {
        openai.shutdown();
    }

    private AiAdvisor advisor() {
        return new AiAdvisor(null, "test-key", null, openai.url("/").toString().replaceAll("/$", ""));
    }

    @Test
    void OpenAI_키만_있으면_JSON_스키마로_묻고_답을_읽는다() throws InterruptedException {
        openai.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"choices":[{"message":{"content":"{\\"keys\\":[{\\"env\\":\\"JWT_SECRET\\",\\"kind\\":\\"generate\\",\\"value\\":\\"\\",\\"hint\\":\\"JWT 서명 키\\"}]}"}}]}"""));

        var answer = advisor().classify("- env=JWT_SECRET");

        assertThat(answer).hasValueSatisfying(keys -> assertThat(keys.keys()).singleElement()
                .satisfies(key -> assertThat(key.kind()).isEqualTo("generate")));
        RecordedRequest request = openai.takeRequest();
        assertThat(request.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-key");
        assertThat(request.getBody().readUtf8()).contains("\"json_schema\"", "\"strict\":true", AiAdvisor.OPENAI_DEFAULT_MODEL);
    }

    @Test
    void 호출이_실패하면_빈_값이라_규칙으로_정한다() {
        openai.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":{}}"));

        assertThat(advisor().diagnose("logs")).isEmpty();
    }

    @Test
    void 키가_없으면_꺼진다() {
        assertThat(AiAdvisor.offline().enabled()).isFalse();
        assertThat(AiAdvisor.offline().classify("x")).isEmpty();
    }
}
