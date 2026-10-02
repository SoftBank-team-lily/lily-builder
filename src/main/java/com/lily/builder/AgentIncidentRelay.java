package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 에이전트 소켓의 수정 사건을 frontend 게이트로 넘긴다.
 * 주소나 토큰이 없으면 보내지 않는다. diff 와 GitHub 는 여기서 하지 않는다.
 */
@Component
public class AgentIncidentRelay {

    private static final Logger log = LoggerFactory.getLogger(AgentIncidentRelay.class);
    private static final int LOG_LIMIT = 8_000;

    private final RemediateProperties properties;
    private final RestClient http;

    @Autowired
    public AgentIncidentRelay(RemediateProperties properties) {
        this(properties, RestClient.create());
    }

    AgentIncidentRelay(RemediateProperties properties, RestClient http) {
        this.properties = properties;
        this.http = http;
    }

    public void accept(JsonNode node) {
        if (properties.frontendUrl() == null || properties.frontendUrl().isBlank()
                || properties.token() == null || properties.token().isBlank()) {
            log.info("on-prem incident dropped: frontend 주소나 토큰이 없다");
            return;
        }
        String app = node.path("app").asText("");
        String signature = node.path("signature").asText("");
        String body = node.path("log").asText("");
        List<String> files = files(node.path("files"));
        if (app.isBlank() || signature.isBlank() || body.isBlank() || files.isEmpty()) {
            log.info("on-prem incident dropped: 사건 필드가 없다");
            return;
        }
        if (body.length() > LOG_LIMIT) {
            body = body.substring(body.length() - LOG_LIMIT);
        }
        Map<String, Object> incident = new LinkedHashMap<>();
        incident.put("app", app);
        incident.put("signature", signature);
        incident.put("log", body);
        incident.put("files", files);
        http.post()
                .uri(properties.frontendUrl().replaceAll("/$", "") + "/api/internal/remediate")
                .header("Authorization", "Bearer " + properties.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(incident)
                .retrieve()
                .toBodilessEntity();
    }

    private static List<String> files(JsonNode node) {
        List<String> paths = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return paths;
        }
        node.forEach(item -> {
            String path = item.asText("");
            if (!path.isBlank() && !path.startsWith("/") && !path.contains("..") && !path.contains("\\")) {
                paths.add(path);
            }
        });
        return paths;
    }
}
