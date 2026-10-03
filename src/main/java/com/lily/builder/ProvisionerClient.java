package com.lily.builder;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * lily-db-provisioner 호출. 온프레미스 앱이 클라우드와 같은 DB 를 쓰도록 DB 를 찾거나 만들고,
 * 온프레미스에서 닿는 주소(터널)로 접속 정보를 받는다.
 */
@Component
public class ProvisionerClient {

    private final RestClient http;
    private final boolean configured;

    public ProvisionerClient(Settings settings) {
        this.configured = !settings.url().isBlank() && !settings.apiToken().isBlank();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.http = RestClient.builder().requestFactory(factory)
                .baseUrl(settings.url().isBlank() ? "http://localhost" : settings.url())
                .defaultHeader("Authorization", "Bearer " + settings.apiToken())
                .build();
    }

    /**
     * 프로젝트(appName) 의 DB 를 찾고 없으면 만든다. lily-cicd 와 같은 projectId 를 쓰므로
     * 클라우드 배포도 같은 DB 를 받는다.
     */
    public Connection ensure(String projectId, String engine, String host, int port) {
        if (!configured) {
            throw new IllegalStateException("provisioner 가 설정되지 않았다 (PROVISIONER_URL, PROVISIONER_API_TOKEN)");
        }
        Database db = find(projectId);
        if (db != null && !db.engine().equals(engine)) {
            throw new IllegalArgumentException("이미 " + db.engine() + " DB 가 있다: " + projectId);
        }
        if (db == null || "FAILED".equals(db.status())) {
            db = create(projectId, engine);
        }
        String id = db.id();
        EnvResponse env = http.get()
                .uri(b -> b.path("/api/databases/{id}/env").queryParam("host", host).queryParam("port", port)
                        .build(id))
                .retrieve().body(EnvResponse.class);
        return new Connection(id, env == null ? Map.of() : env.env());
    }

    /**
     * 프로젝트 DB 에 pgroll 을 켠다 (관리자 계정으로 init, 프로젝트 계정에 권한). 여러 번 불러도 된다.
     * 온프레미스 앱이 RDS 를 쓰면서 pgroll 마이그레이션을 받거나, pgroll 을 쓰는 DB 를 RDS 로 옮길 때 쓴다
     */
    public void enablePgroll(String projectId) {
        if (!configured) {
            throw new IllegalStateException("provisioner 가 설정되지 않았다 (PROVISIONER_URL, PROVISIONER_API_TOKEN)");
        }
        Database db = find(projectId);
        if (db == null) {
            throw new IllegalStateException("DB 가 없다: " + projectId);
        }
        http.post().uri("/api/databases/{id}/pgroll", db.id()).retrieve().toBodilessEntity();
    }

    /**
     * 이미 있는 프로젝트 DB 의 접속 정보. 만들지 않는다 (앱을 다른 클라우드로 옮길 때 원본 DB).
     *
     * @param host null 이면 provisioner 의 공개 주소 (RDS·Cloud SQL 엔드포인트)
     * @return DB 가 없으면 empty
     */
    public java.util.Optional<Connection> existing(String projectId, String host, Integer port) {
        if (!configured) {
            throw new IllegalStateException("provisioner 가 설정되지 않았다 (PROVISIONER_URL, PROVISIONER_API_TOKEN)");
        }
        Database db = find(projectId);
        if (db == null) {
            return java.util.Optional.empty();
        }
        EnvResponse env = http.get()
                .uri(b -> {
                    b.path("/api/databases/{id}/env");
                    if (host != null) {
                        b.queryParam("host", host).queryParam("port", port);
                    }
                    return b.build(db.id());
                })
                .retrieve().body(EnvResponse.class);
        return java.util.Optional.of(new Connection(db.id(), env == null ? Map.of() : env.env()));
    }

    /** 프로젝트 DB 의 엔진. 없으면 empty */
    public java.util.Optional<String> engine(String projectId) {
        Database db = find(projectId);
        return db == null ? java.util.Optional.empty() : java.util.Optional.of(db.engine());
    }

    /** 프로젝트 DB 를 지운다 (DROP). 없으면 아무것도 하지 않는다. 옮기다 실패해 만든 빈 DB 를 치울 때 쓴다 */
    public void delete(String projectId) {
        Database db = find(projectId);
        if (db != null) {
            http.delete().uri("/api/databases/{id}", db.id()).retrieve().toBodilessEntity();
        }
    }

    private Database find(String projectId) {
        List<Database> found = http.get()
                .uri(b -> b.path("/api/databases").queryParam("projectId", projectId).build())
                .retrieve().body(new org.springframework.core.ParameterizedTypeReference<>() { });
        return found == null || found.isEmpty() ? null : found.get(0);
    }

    private Database create(String projectId, String engine) {
        try {
            return http.post().uri("/api/databases")
                    .body(Map.of("projectId", projectId, "engine", engine))
                    .retrieve().body(Database.class);
        } catch (RestClientResponseException e) {
            // 동시에 lily-cicd 가 먼저 만들었으면 그 DB 를 쓴다
            if (e.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(409))) {
                Database db = find(projectId);
                if (db != null) {
                    return db;
                }
            }
            throw e;
        }
    }

    record Database(String id, String projectId, String engine, String status) {
    }

    record EnvResponse(String databaseId, Map<String, String> env) {
    }

    public record Connection(String databaseId, Map<String, String> env) {
    }

    /** lily-db-provisioner 주소와 API 토큰 (PROVISIONER_API_TOKEN) */
    @ConfigurationProperties("lily.builder.provisioner")
    public record Settings(@DefaultValue("") String url, @DefaultValue("") String apiToken) {
    }
}
