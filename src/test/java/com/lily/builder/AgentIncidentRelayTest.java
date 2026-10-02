package com.lily.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class AgentIncidentRelayTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void missingFrontendDoesNotCall() throws Exception {
        AgentIncidentRelay relay = new AgentIncidentRelay(new RemediateProperties(false, "", "", ""), RestClient.create());

        relay.accept(json.readTree("""
                {"type":"remediate","app":"blog","signature":"Error a.js:1","log":"Error","files":["a.js"]}"""));
    }

    @Test
    void postsTheIncidentWithoutTheSocketType() throws Exception {
        try (MockWebServer frontend = new MockWebServer()) {
            frontend.enqueue(new MockResponse().setResponseCode(200));
            AgentIncidentRelay relay = new AgentIncidentRelay(
                    new RemediateProperties(false, "", frontend.url("/").toString(), "deploy-token"),
                    RestClient.create());

            relay.accept(json.readTree("""
                    {"type":"remediate","app":"blog","signature":"Error a.js:1","log":"Error: bad","files":["a.js","../x"]}"""));

            RecordedRequest request = frontend.takeRequest();
            assertThat(request.getPath()).isEqualTo("/api/internal/remediate");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer deploy-token");
            assertThat(request.getBody().readUtf8()).contains("\"app\":\"blog\"", "\"a.js\"").doesNotContain("remediate", "..");
        }
    }
}
