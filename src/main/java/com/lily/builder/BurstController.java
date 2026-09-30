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
 * </pre>
 */
@RestController
@RequestMapping("/api/burst")
public class BurstController {

    private final BuildService builds;
    private final CicdClient cicd;

    public BurstController(BuildService builds, CicdClient cicd) {
        this.builds = builds;
        this.cicd = cicd;
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
