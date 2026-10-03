package com.lily.builder.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lily.builder.BuildRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.Set;

/** AWS/GKE에 배치한 Lily worker의 기존 /api/builds 계약으로 전달한다. POST는 재시도하지 않는다. */
@Component
public class CloudWorkers {
    private final CloudProperties props;
    private final ObjectMapper json;
    private final RestClient http;
    private static final Set<String> STATUSES = Set.of("QUEUED", "BUILDING", "DEPLOYING", "SUCCEEDED", "FAILED", "ROLLED_BACK");
    public CloudWorkers(CloudProperties props, ObjectMapper json) {
        this.props = props; this.json = json;
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(java.net.HttpURLConnection connection, String method) throws java.io.IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(2000); factory.setReadTimeout(15000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }
    public JsonNode start(String provider, BuildRequest build) { return exchange(provider, null, build); }
    public JsonNode get(String provider, String id) { return exchange(provider, id, null); }
    private JsonNode exchange(String provider, String id, BuildRequest build) {
        var worker = props.worker(provider);
        if (!CloudProperties.usable(worker)) throw new Unavailable("worker_unconfigured");
        if (id != null && !id.matches("[a-zA-Z0-9-]{1,64}")) throw new Unavailable("invalid_build_id");
        String url = worker.url().replaceAll("/+$", "") + "/api/builds" + (id == null ? "" : "/" + id);
        try {
            RestClient.RequestBodySpec request = http.method(build == null ? org.springframework.http.HttpMethod.GET : org.springframework.http.HttpMethod.POST).uri(url);
            if (worker.token() != null && !worker.token().isBlank()) request.header("Authorization", "Bearer " + worker.token());
            if (build != null) request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(build);
            return request.exchange((req, response) -> {
                // 4xx 면 worker 가 빌드를 접수하지 않은 것이 확실하다. 그 밖의 실패는 접수됐을 수도 있다
                if (!response.getStatusCode().is2xxSuccessful()) throw new Unavailable(build == null ? "worker_unavailable"
                    : response.getStatusCode().is4xxClientError() ? "dispatch_rejected" : "dispatch_unconfirmed");
                byte[] body = response.getBody().readNBytes(262145);
                if (body.length > 262144) throw new Unavailable("invalid_worker_response");
                JsonNode node = json.readTree(body);
                if (node == null || !node.path("id").asText().matches("[a-zA-Z0-9-]{1,64}") || !STATUSES.contains(node.path("status").asText()))
                    throw new Unavailable("invalid_worker_response");
                if (id != null && !id.equals(node.path("id").asText())) throw new Unavailable("invalid_worker_response");
                var safe = json.createObjectNode();
                for (String field : Set.of("id", "appName", "status", "url", "stage", "stageName", "createdAt", "updatedAt", "commit"))
                    if (node.has(field)) safe.set(field, node.get(field));
                return safe;
            });
        } catch (Unavailable e) { throw e; }
        catch (RuntimeException e) { throw new Unavailable(build == null ? "worker_unavailable" : "dispatch_unconfirmed"); }
    }
    public static final class Unavailable extends RuntimeException {
        public Unavailable(String code) { super(code); }
    }
}
