package com.lily.builder.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.List;

/** 운영 수집기가 제공한 같은 부하 프로필의 가격·관측 스냅샷. URL은 서버 설정에서만 읽는다. */
@Component
public class CloudCatalog {
    private final CloudProperties props;
    private final ObjectMapper json;
    private final RestClient http;
    public CloudCatalog(CloudProperties props, ObjectMapper json) {
        this.props = props; this.json = json;
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(java.net.HttpURLConnection connection, String method) throws java.io.IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(2000); factory.setReadTimeout(3000);
        http = RestClient.builder().requestFactory(factory).build();
    }
    record Snapshot(List<CloudPolicy.Candidate> candidates) {}
    public List<CloudPolicy.Candidate> read() {
        if (!CloudProperties.validUrl(props.catalogUrl())) return List.of();
        try {
            var request = http.get().uri(props.catalogUrl());
            if (props.catalogToken() != null && !props.catalogToken().isBlank()) request.header("Authorization", "Bearer " + props.catalogToken());
            return request.exchange((req, response) -> {
                if (response.getStatusCode().value() != 200) return List.of();
                byte[] bytes = response.getBody().readNBytes(131073);
                if (bytes.length > 131072) return List.of();
                Snapshot snapshot = json.readValue(bytes, Snapshot.class);
                return snapshot.candidates() == null || snapshot.candidates().size() > 100 ? List.of() : snapshot.candidates();
            });
        } catch (RuntimeException ignored) { return List.of(); }
    }
}
