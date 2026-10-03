package com.lily.builder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** lily-cicd 호출. 배포는 스키마 마이그레이션과 블루-그린 Ready 대기까지 끝나야 응답이 온다 */
@Component
public class CicdClient {

    private final RestClient http;

    @Autowired
    public CicdClient(BuilderProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        // lily-cicd 는 스키마 마이그레이션과 Ready 대기(기본 120초)까지 끝나고 응답한다. lily-cicd 요청 타임아웃(400초)보다 길게
        factory.setReadTimeout(Duration.ofSeconds(420));
        this.http = RestClient.builder().requestFactory(factory).baseUrl(props.cicdUrl()).build();
    }

    CicdClient(RestClient http) {
        this.http = http;
    }

    /** @param migrations 파일명 → SQL. 비어 있으면 보내지 않는다 (앱의 Flyway 가 스키마를 맡는다) */
    public Result deploy(BuildRequest request, String image, String version, Map<String, String> migrations) {
        return deploy(request, image, version, migrations, List.of());
    }

    /** @param aliases 같은 Service 로 보내는 추가 Ingress 호스트 (엣지 Worker 의 클라우드 주소). 비어 있으면 보내지 않는다 */
    public Result deploy(BuildRequest request, String image, String version, Map<String, String> migrations,
                         List<String> aliases) {
        Map<String, Object> body = new HashMap<>();
        body.put("appName", request.appName());
        body.put("imageUrl", image);
        body.put("targetPort", request.targetPortOrDefault());
        body.put("appVersion", version);
        body.put("readinessPath", blankToNull(request.readinessPath()));
        body.put("livenessPath", blankToNull(request.livenessPath()));
        body.put("database", blankToNull(request.database()));
        body.put("extraEnv", request.env() == null ? Map.of() : request.env());
        body.put("host", blankToNull(request.host()));
        body.put("canaryPath", blankToNull(request.canaryPath()));
        if (aliases != null && !aliases.isEmpty()) {
            body.put("aliases", aliases);
        }
        if (request.givenDatabase()) {
            // 온프레미스 DB 를 역방향 터널로 쓴다. lily-cicd 는 DB 를 만들지 않고 이 값을 슬롯 Secret 에 넣는다
            body.put("database", null);
            body.put("databaseEnv", request.databaseEnv());
        } else if (migrations != null && !migrations.isEmpty()) {
            body.put("migrations", migrations);
        }
        return http.post().uri("/api/deployments").body(body).retrieve().body(Result.class);
    }

    /** 진행 중이거나 마지막 배포의 단계. 기록이 없으면 null */
    public Progress progress(String appName) {
        return http.get().uri("/api/deployments/{app}/progress", appName).retrieve()
                .onStatus(s -> s.value() == 404, (req, res) -> { })
                .body(Progress.class);
    }

    /** 활성 슬롯 상태. 앱이 없으면 null */
    public AppStatus status(String appName) {
        return http.get().uri("/api/apps/{app}", appName).retrieve()
                .onStatus(s -> s.value() == 404, (req, res) -> { })
                .body(AppStatus.class);
    }

    /** 활성 슬롯 레플리카 조정 */
    public AppStatus scale(String appName, int replicas) {
        return http.put().uri("/api/apps/{app}/replicas", appName)
                .body(Map.of("replicas", replicas))
                .retrieve().body(AppStatus.class);
    }

    /** 직전 릴리스로 앱과 스키마를 되돌린다. lily-cicd 의 상태 코드와 본문을 그대로 돌려준다 (200, 409, 400, 500) */
    public Passthrough rollback(String appName, boolean appOnly) {
        return exchange(http.post().uri("/api/deployments/{app}/rollback", appName).body(Map.of("appOnly", appOnly)));
    }

    /** 모든 슬롯을 0 으로 줄인다. Service·Ingress·DB 는 남는다 (200, 404, 409) */
    public Passthrough stop(String appName) {
        return exchange(http.post().uri("/api/apps/{app}/stop", appName));
    }

    /** 트래픽을 받는 슬롯을 기본 레플리카로 되돌린다 (200, 404, 409) */
    public Passthrough start(String appName) {
        return exchange(http.post().uri("/api/apps/{app}/start", appName));
    }

    /** 앱을 클러스터에서 지운다. database 면 DB 도 DROP (200, 404, 409) */
    public Passthrough remove(String appName, boolean database) {
        return exchange(http.delete().uri(uri -> uri.path("/api/apps/{app}")
                .queryParam("database", database).build(appName)));
    }

    /** 슬롯별 릴리스와 롤백 가능 여부 */
    public Passthrough release(String appName) {
        return exchange(http.get().uri("/api/deployments/{app}", appName));
    }

    /** 스키마 이력과 pgroll 롤백 창 (200, 앱이 없으면 404) */
    public Passthrough schema(String appName) {
        return exchange(http.get().uri("/api/deployments/{app}/schema", appName));
    }

    /** pgroll 롤백 창을 바로 닫는다 (200 COMPLETED, 열린 창이 없거나 진행 중이면 409) */
    public Passthrough completeSchema(String appName) {
        return exchange(http.post().uri("/api/deployments/{app}/schema/complete", appName));
    }

    private static Passthrough exchange(RestClient.RequestHeadersSpec<?> spec) {
        return spec.exchange((req, res) -> new Passthrough(res.getStatusCode().value(),
                new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** lily-cicd 의 DeploymentResultDto */
    public record Result(String status, String activeColor, String targetHostUrl, String schemaVersion,
                         java.util.List<String> logs) {}

    /** lily-cicd 의 AppController.AppStatus */
    public record AppStatus(String appName, String namespace, String activeColor, int replicas, int readyReplicas) {}

    /** lily-cicd 의 DeployProgress.Snapshot. stage 는 ready, canary-traffic, canary-analysis, service 등 */
    /** @param image 이번 배포 이미지. 없으면 옛 cicd 다 */
    public record Progress(String stage, String detail, java.time.Instant updatedAt, String image) {}

    /** lily-cicd 의 DeployController.DeployError. canary 판정 실패면 status 가 ROLLED_BACK */
    public record DeployError(String status, String message, java.util.List<String> logs) {}

    /** lily-cicd 응답 그대로 (JSON 본문) */
    public record Passthrough(int status, String body) {}
}
