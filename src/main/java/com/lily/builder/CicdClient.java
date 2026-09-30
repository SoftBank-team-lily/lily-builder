package com.lily.builder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** lily-cicd 의 POST /api/deployments 호출. 블루-그린 Ready 대기까지 끝나야 응답이 온다 */
@Component
public class CicdClient {

    private final RestClient http;

    @Autowired
    public CicdClient(BuilderProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        // lily-cicd 는 Ready 대기(기본 120초)까지 끝나고 응답한다
        factory.setReadTimeout(Duration.ofSeconds(300));
        this.http = RestClient.builder().requestFactory(factory).baseUrl(props.cicdUrl()).build();
    }

    CicdClient(RestClient http) {
        this.http = http;
    }

    public Result deploy(BuildRequest request, String image, String version) {
        Map<String, Object> body = new HashMap<>();
        body.put("appName", request.appName());
        body.put("imageUrl", image);
        body.put("targetPort", request.targetPort());
        body.put("appVersion", version);
        body.put("readinessPath", blankToNull(request.readinessPath()));
        body.put("livenessPath", blankToNull(request.livenessPath()));
        body.put("database", blankToNull(request.database()));
        body.put("extraEnv", request.env() == null ? Map.of() : request.env());
        return http.post().uri("/api/deployments").body(body).retrieve().body(Result.class);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** lily-cicd 의 DeploymentResultDto */
    public record Result(String status, String activeColor, String targetHostUrl, java.util.List<String> logs) {}
}
