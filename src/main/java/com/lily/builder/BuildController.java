package com.lily.builder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@RestController
public class BuildController {

    private static final Pattern APP_NAME = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
    private static final Pattern UPSTREAM_HOST =
            Pattern.compile("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?)+");

    private final BuildService service;
    private final ClusterApps clusterApps;
    private final CicdClient cicd;
    private final AgentDeployService agents;
    private final ObjectMapper json = new ObjectMapper();

    public BuildController(BuildService service, ClusterApps clusterApps, CicdClient cicd, AgentDeployService agents) {
        this.service = service;
        this.clusterApps = clusterApps;
        this.cicd = cicd;
        this.agents = agents;
    }

    /** 빌드·배포는 몇 분 걸려서 바로 id 만 돌려준다. 진행 상황은 GET 으로 본다 */
    @PostMapping("/api/builds")
    public ResponseEntity<Build> start(@Valid @RequestBody BuildRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.start(request));
    }

    /** 배포 전에 레포가 쓰는 DB 를 본다. 화면이 사용자에게 맞는지 묻는다 */
    @PostMapping("/api/detect")
    public ResponseEntity<?> detect(@Valid @RequestBody DetectRequest request) {
        try {
            return ResponseEntity.ok(service.inspect(request));
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    /** 배포 이력 (빌더 기준, 최신순) */
    @GetMapping("/api/builds")
    public List<Build> history() {
        return service.history();
    }

    @GetMapping("/api/builds/{id}")
    public ResponseEntity<Build> get(@PathVariable String id) {
        return service.get(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** 지금 k3s 에 떠 있는 앱 (클러스터 기준) */
    @GetMapping("/api/apps")
    public List<ClusterApps.RunningApp> apps() {
        return clusterApps.list();
    }

    /**
     * 직전 릴리스로 앱과 스키마를 되돌린다. lily-cicd 응답(200 ROLLED_BACK/PARTIAL, 409 거부)을 그대로 넘긴다.
     * 되돌릴 수 없는 스키마면 {@code appOnly=true} 로 앱만 되돌릴 수 있다.
     */
    @PostMapping("/api/apps/{appName}/rollback")
    public ResponseEntity<String> rollback(@PathVariable String appName,
                                           @RequestBody(required = false) RollbackRequest request) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            Optional<String> onprem = agents.rollback(appName);
            if (onprem.isPresent()) {
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(onprem.get());
            }
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(rejected(e.getMessage()));
        }
        return passthrough(cicd.rollback(appName, request != null && request.appOnly()));
    }

    /** 앱을 내린다 (모든 슬롯 0). Service·Ingress·DB 가 남아서 start 로 되살린다. 배포 중이면 409 */
    @PostMapping("/api/apps/{appName}/stop")
    public ResponseEntity<String> stop(@PathVariable String appName) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        return passthrough(cicd.stop(appName));
    }

    /** 내린 앱을 기본 레플리카로 다시 띄운다. 배포 중이면 409 */
    @PostMapping("/api/apps/{appName}/start")
    public ResponseEntity<String> startApp(@PathVariable String appName) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        return passthrough(cicd.start(appName));
    }

    /**
     * 앱을 클러스터에서 지운다 (Deployment, Service, Ingress, Secret, 릴리스 기록).
     * {@code database=true} 면 DB 도 DROP 한다. 없으면 404, 배포 중이면 409
     */
    @DeleteMapping("/api/apps/{appName}")
    public ResponseEntity<String> remove(@PathVariable String appName,
                                         @RequestParam(defaultValue = "false") boolean database) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        return passthrough(cicd.remove(appName, database));
    }

    /**
     * 클라우드 앱의 공개 주소({app}.apps...)는 두고, Ingress 가 요청을 온프레미스 공개 호스트로 넘기게 한다.
     * 클라우드 앱을 내 PC 로 옮길 때 쓴다. 본문 {"host":"{app}.{플랫폼 존}"}
     */
    @PutMapping("/api/apps/{appName}/upstream")
    public ResponseEntity<String> pointUpstream(@PathVariable String appName, @RequestBody UpstreamRequest request) {
        if (!APP_NAME.matcher(appName).matches() || request == null || request.host() == null
                || !UPSTREAM_HOST.matcher(request.host()).matches()) {
            return ResponseEntity.badRequest().build();
        }
        return passthrough(cicd.pointUpstream(appName, request.host()));
    }

    /** 앱 Ingress 를 클러스터 Service 로 되돌린다 (클라우드로 돌아올 때) */
    @DeleteMapping("/api/apps/{appName}/upstream")
    public ResponseEntity<String> restoreUpstream(@PathVariable String appName) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        return passthrough(cicd.restoreUpstream(appName));
    }

    @GetMapping("/api/apps/{appName}/upstream")
    public ResponseEntity<String> upstream(@PathVariable String appName) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        return passthrough(cicd.upstream(appName));
    }

    public record UpstreamRequest(String host) {
    }

    /** 온프레미스 에이전트에 거점 전환을 보낸다. 최근 성공이 온프레미스가 아니면 409 */
    @PostMapping("/api/apps/{appName}/home")
    public ResponseEntity<String> home(@PathVariable String appName, @RequestBody(required = false) HomeRequest request) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        String target = request == null ? "" : request.home();
        try {
            Optional<String> moved = agents.home(appName, target,
                    request != null && Boolean.TRUE.equals(request.migrateDatabase()));
            if (moved.isEmpty()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(rejected("온프레미스 에이전트를 찾지 못했다"));
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(moved.get());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(rejected(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(rejected(e.getMessage()));
        }
    }

    /** @param migrateDatabase 앱 DB 도 옮긴다 (클라우드로: 내 PC → RDS, 온프레미스로: RDS → 내 PC) */
    public record HomeRequest(String home, Boolean migrateDatabase) {
    }

    /** 온프레미스 앱의 클라우드 버스팅 상태. 에이전트가 몇 초마다 보낸 값이다 */
    @GetMapping("/api/apps/{appName}/burst")
    public ResponseEntity<AgentHub.Burst> burst(@PathVariable String appName) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        return agents.burstState(appName).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** 버스팅 켜기·끄기와 클라우드 비율. 에이전트에 보내기만 하고, 반영은 다음 상태에서 보인다 */
    @PutMapping("/api/apps/{appName}/burst")
    public ResponseEntity<?> burst(@PathVariable String appName, @Valid @RequestBody BurstRequest request) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return agents.burst(appName, request.enabled(), request.cloudPercent())
                    .<ResponseEntity<?>>map(state -> ResponseEntity.accepted().body(state))
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                    .body(rejected(e.getMessage()));
        }
    }

    public record BurstRequest(@NotNull Boolean enabled, @NotNull @Min(0) @Max(100) Integer cloudPercent) {
    }

    /** 슬롯별 릴리스(이미지, 스키마 버전, 배포 시각)와 롤백 가능 여부 */
    @GetMapping("/api/apps/{appName}/release")
    public ResponseEntity<String> release(@PathVariable String appName) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        return passthrough(cicd.release(appName));
    }

    private String rejected(String message) {
        try {
            return json.writeValueAsString(Map.of("status", "REJECTED", "message", message == null ? "" : message));
        } catch (JsonProcessingException e) {
            return "{\"status\":\"REJECTED\"}";
        }
    }

    private static ResponseEntity<String> passthrough(CicdClient.Passthrough response) {
        return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON).body(response.body());
    }

    public record RollbackRequest(boolean appOnly) {}
}
