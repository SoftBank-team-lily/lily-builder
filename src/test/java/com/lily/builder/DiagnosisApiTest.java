package com.lily.builder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisApiTest {
    private static final String BODY = """
            {"app":"blog","namespace":"default","observedAt":"2026-10-02T00:00:00Z",
             "evidence":[{"id":"pod-1","source":"pods","signal":"oom","summary":"OOMKilled"}],"missingSources":[]}
            """;
    private MockMvc mvc;
    private AtomicInteger calls;

    @BeforeEach void setup() {
        calls = new AtomicInteger();
        mvc = api("test-token");
    }

    private MockMvc api(String token) {
        var service = new DiagnosisService("") {
            @Override public Diagnosis.Response analyze(Diagnosis.Request request) {
                calls.incrementAndGet();
                return super.analyze(request);
            }
        };
        return MockMvcBuilders.standaloneSetup(new DiagnosisController(service)).addFilters(new DiagnosisTokenFilter(token)).build();
    }

    @Test void validAuthenticatedRequestReturnsCitations() throws Exception {
        mvc.perform(post("/api/diagnoses").servletPath("/api/diagnoses").header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("rules"))
                .andExpect(jsonPath("$.category").value("resources"))
                .andExpect(jsonPath("$.evidenceIds[0]").value("pod-1"));
    }

    @Test void missingOrWrongTokenDoesNotAnalyze() throws Exception {
        for (String token : new String[]{"", "Bearer wrong"}) {
            mvc.perform(post("/api/diagnoses").servletPath("/api/diagnoses").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("unauthorized"));
        }
        assertThat(calls).hasValue(0);
    }

    @Test void missingConfiguredTokenDisablesEvenWithCallerToken() throws Exception {
        api("").perform(post("/api/diagnoses").servletPath("/api/diagnoses").header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("diagnosis_disabled"));
        assertThat(calls).hasValue(0);
    }

    @Test void validatesNullsDuplicatesNamesSignalsAndSummaryBeforeAnalysis() throws Exception {
        String original = "{\"id\":\"pod-1\",\"source\":\"pods\",\"signal\":\"oom\",\"summary\":\"OOMKilled\"}";
        for (String body : new String[]{BODY.replace("\"blog\"", "\"../other\""), BODY.replace("\"oom\"", "\"run_shell\""),
                BODY.replace(original, original + "," + original), BODY.replace("OOMKilled", "a".repeat(2001)),
                BODY.replace("[" + original + "]", "[null]"), BODY.replace("\"2026-10-02T00:00:00Z\"", "null"), "{"}) {
            mvc.perform(post("/api/diagnoses").servletPath("/api/diagnoses").header("Authorization", "Bearer test-token")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        }
        assertThat(calls).hasValue(0);
    }

    @Test void limitsWholeBodyEvenWhenContentLengthIsUnknown() throws Exception {
        var request = new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setServletPath("/api/diagnoses");
        request.addHeader("Authorization", "Bearer test-token");
        request.setContent(" ".repeat(DiagnosisTokenFilter.MAX_BODY_BYTES + 1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        new DiagnosisTokenFilter("test-token").doFilter(request, response, (req, res) -> calls.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("request_too_large");
        assertThat(calls).hasValue(0);
    }
}
