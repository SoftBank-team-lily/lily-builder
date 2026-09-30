package com.lily.builder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 클라우드 버스팅 API. 온프레미스 에이전트가 부른다 (BurstTokenFilter 로 토큰 인증).
 *
 * <pre>
 * 온프레미스 배포 성공 → POST /api/burst/apps/{app}/standby   클라우드에 같은 레포를 빌드·배포하고 레플리카 0 으로 대기
 * 온프레미스 과부하    → PUT  /api/burst/apps/{app}/replicas  대기 슬롯을 올린다
 * 준비 확인            → GET  /api/burst/apps/{app}           readyReplicas 가 1 이상이면 넘기기 시작
 * 부하 해소            → PUT  /api/burst/apps/{app}/replicas  0 으로 내린다
 * 온프레미스 DB        → POST /api/burst/apps/{app}/database  클라우드와 같은 DB 를 터널 주소로 받는다
 * </pre>
 */
@RestController
@RequestMapping("/api/burst")
public class BurstController {

    private final BuildService builds;
    private final CicdClient cicd;
    private final ProvisionerClient provisioner;

    public BurstController(BuildService builds, CicdClient cicd, ProvisionerClient provisioner) {
        this.builds = builds;
        this.cicd = cicd;
        this.provisioner = provisioner;
    }

    /**
     * 온프레미스 앱의 DB. projectId = appName 이라 클라우드 배포(lily-cicd)와 같은 DB 를 쓴다.
     * host/port 는 온프레미스에서 RDS 로 가는 터널 주소. 계정·비밀번호는 클라우드와 같다
     */
    @PostMapping("/apps/{appName}/database")
    public ProvisionerClient.Connection database(@PathVariable String appName,
                                                 @Valid @RequestBody DatabaseRequest request) {
        return provisioner.ensure(appName, request.engine(), request.host(), request.port());
    }

    public record DatabaseRequest(
            @NotNull @jakarta.validation.constraints.Pattern(regexp = "postgres|mysql") String engine,
            @NotNull @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9.-]{1,253}") String host,
            @NotNull @Min(1) @Max(65535) Integer port) {
    }

    /** 대기 배포. host 에는 온프레미스 공개 주소를 넣는다 (그 Host 헤더로 넘어온 요청을 클라우드 Ingress 가 받도록) */
    @PostMapping("/apps/{appName}/standby")
    public ResponseEntity<Build> standby(@PathVariable String appName, @Valid @RequestBody BuildRequest request) {
        if (!appName.equals(request.appName())) {
            return ResponseEntity.badRequest().build();
        }
        BuildRequest standby = new BuildRequest(request.repoUrl(), request.branch(), request.token(),
                request.rootDir(), request.appName(), request.targetPort(), request.database(),
                request.readinessPath(), request.livenessPath(), request.env(), request.host(), true);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(builds.start(standby));
    }

    @GetMapping("/builds/{id}")
    public ResponseEntity<Build> build(@PathVariable String id) {
        return builds.get(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/apps/{appName}")
    public ResponseEntity<CicdClient.AppStatus> status(@PathVariable String appName) {
        CicdClient.AppStatus status = cicd.status(appName);
        return status == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(status);
    }

    @PutMapping("/apps/{appName}/replicas")
    public CicdClient.AppStatus scale(@PathVariable String appName, @Valid @RequestBody ScaleRequest request) {
        return cicd.scale(appName, request.replicas());
    }

    public record ScaleRequest(@NotNull @Min(0) @Max(5) Integer replicas) {
    }
}
