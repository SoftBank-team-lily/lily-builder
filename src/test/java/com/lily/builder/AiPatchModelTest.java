package com.lily.builder;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class AiPatchModelTest {

    private MockWebServer groq;

    @BeforeEach
    void start() throws IOException {
        groq = new MockWebServer();
        groq.start();
    }

    @AfterEach
    void stop() throws IOException {
        groq.shutdown();
    }

    @Test
    void groqUsesStrictJsonAndReturnsTheDiff() throws InterruptedException {
        groq.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"choices":[{"message":{"content":"{\\"diff\\":\\"--- a/A.java\\"}"}}]}"""));
        AiPatchModel model = new AiPatchModel(null, "gsk-test", AiPatchModel.GROQ_DEFAULT_MODEL,
                groq.url("/").toString().replaceAll("/$", ""));

        assertThat(model.diff("로그")).contains("--- a/A.java");

        RecordedRequest request = groq.takeRequest();
        assertThat(request.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer gsk-test");
        assertThat(request.getBody().readUtf8()).contains("\"json_schema\"", "\"strict\":true", AiPatchModel.GROQ_DEFAULT_MODEL);
    }

    @Test
    void failedCallReturnsAnEmptyDiff() {
        groq.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":{}}"));
        AiPatchModel model = new AiPatchModel(null, "gsk-test", "openai/gpt-oss-20b",
                groq.url("/").toString().replaceAll("/$", ""));

        assertThat(model.diff("로그")).isEmpty();
    }

    @Test
    void missingJevKeyDoesNotBlockTheDraft() {
        assertThat(RemediateConfiguration.defectGateFor("").codeDefect("로그")).contains(true);
        assertThat(RemediateConfiguration.defectGateFor(null).codeDefect("로그")).contains(true);
    }
}
