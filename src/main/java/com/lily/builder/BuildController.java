package com.lily.builder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

    private final BuildService service;
    private final ClusterApps clusterApps;
    /** 앱이 배포된 클라우드(AWS·GCP)의 lily-cicd 와 주소 */
    private final CloudRouting clouds;
    private final AgentDeployService agents;
    private final AppAddress addresses;
    private final EdgeWorker edge;
    /** 온프레미스 앱 삭제. 테스트 생성자에서는 null (클라우드 앱만 지운다) */
    private final OnPremAppRemoval onPrem;
    /** 다른 클라우드로 옮기는 중인 앱의 배포·롤백·내리기를 막는다. 테스트 생성자에서는 null */
    private final AppMigration migration;
    private final ObjectMapper json = new ObjectMapper();

    BuildController(BuildService service, ClusterApps clusterApps, CicdClient cicd, AgentDeployService agents,
                    AppAddress addresses) {
        this(service, clusterApps, CloudRouting.awsOnly(cicd), agents, addresses, EdgeWorker.disabled(), null);
    }

    public BuildController(BuildService service, ClusterApps clusterApps, CloudRouting clouds, AgentDeployService agents,
                           AppAddress addresses, EdgeWorker edge, OnPremAppRemoval onPrem) {
        this(service, clusterApps, clouds, agents, addresses, edge, onPrem, null);
    }

    @Autowired
    public BuildController(BuildService service, ClusterApps clusterApps, CloudRouting clouds, AgentDeployService agents,
                           AppAddress addresses, EdgeWorker edge, OnPremAppRemoval onPrem, AppMigration migration) {
        this.migration = migration;
        this.onPrem = onPrem;
        this.edge = edge;
        this.service = service;
        this.clusterApps = clusterApps;
        this.clouds = clouds;
        this.agents = agents;
        this.addresses = addresses;
    }

    /** 빌드·배포는 몇 분 걸려서 바로 id 만 돌려준다. 진행 상황은 GET 으로 본다 */
    @PostMapping("/api/builds")
    public ResponseEntity<?> start(@Valid @RequestBody BuildRequest request) {
        Optional<String> conflict = migration == null ? Optional.empty() : migration.conflict(request);
        if (conflict.isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                    .body(rejected(conflict.get()));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.start(request));
    }

    /** 다른 클라우드로 옮기는 중이면 409. 아니면 null */
    private ResponseEntity<String> moving(String appName) {
        if (migration == null || !migration.inProgress(appName)) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body(rejected(appName + " 은 다른 클라우드로 옮기는 중이다"));
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

    /**
     * 진행 중인 배포를 멈춘다. 클라우드는 lily-cicd 로 넘기기 전(QUEUED·BUILDING)까지, 온프레미스는 에이전트가
     * 트래픽을 새 버전으로 바꾸기 전까지 된다. 202 와 CANCELLED 빌드, 이미 끝났거나 멈출 수 없으면 409
     */
    @PostMapping("/api/builds/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id) {
        Optional<Build> build = service.get(id);
        if (build.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            Build cancelled = AgentDeployService.onPrem(build.get()) ? agents.cancel(id) : service.cancel(id);
            return ResponseEntity.accepted().body(cancelled);
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                    .body(rejected(e.getMessage()));
        }
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
        if (moving(appName) != null) {
            return moving(appName);
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
        return passthrough(clouds.cicdOf(appName).rollback(appName, request != null && request.appOnly()));
    }

    /** 앱을 내린다 (모든 슬롯 0). Service·Ingress·DB 가 남아서 start 로 되살린다. 배포 중이면 409 */
    @PostMapping("/api/apps/{appName}/stop")
    public ResponseEntity<String> stop(@PathVariable String appName) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        if (moving(appName) != null) {
            return moving(appName);
        }
        return passthrough(clouds.cicdOf(appName).stop(appName));
    }

    /** 내린 앱을 기본 레플리카로 다시 띄운다. 배포 중이면 409 */
    @PostMapping("/api/apps/{appName}/start")
    public ResponseEntity<String> startApp(@PathVariable String appName) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        if (moving(appName) != null) {
            return moving(appName);
        }
        return passthrough(clouds.cicdOf(appName).start(appName));
    }

    /**
     * 앱을 지운다. 클라우드 앱은 클러스터에서 (Deployment, Service, Ingress, Secret, 릴리스 기록),
     * 온프레미스 앱은 PC 의 컨테이너와 공개 주소, 클라우드 대기 배포까지 ({@link OnPremAppRemoval}).
     * {@code database=true} 면 DB 도 DROP 한다. 없으면 404, 배포·거점 전환 중이면 409
     */
    @DeleteMapping("/api/apps/{appName}")
    public ResponseEntity<String> remove(@PathVariable String appName,
                                         @RequestParam(defaultValue = "false") boolean database) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        if (moving(appName) != null) {
            return moving(appName);
        }
        if (onPrem != null) {
            try {
                Optional<Map<String, Object>> removed = onPrem.remove(appName, database);
                if (removed.isPresent()) {
                    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                            .body(json.writeValueAsString(removed.get()));
                }
            } catch (IllegalStateException e) {
                return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body(rejected(e.getMessage()));
            } catch (JsonProcessingException e) {
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{\"status\":\"REMOVED\"}");
            }
        }
        CicdClient.Passthrough removed = clouds.cicdOf(appName).remove(appName, database);
        if (removed.status() == 200 && migration != null) {
            // 다른 클라우드로 옮긴 뒤 정리 전이면 원본 클러스터의 replicas 0 앱과 DB 도 지운다
            migration.onRemoved(appName);
        }
        if (removed.status() == 200 && addresses.enabled()) {
            try {
                addresses.removeCloud(appName);
            } catch (RuntimeException e) {
                // 남은 레코드는 ALB 의 404 로 끝난다. 앱 삭제는 성공이다
            }
        }
        if (removed.status() == 200 && edge.enabled()) {
            try {
                edge.detach(appName);
            } catch (RuntimeException e) {
                // 남은 라우트는 PC 장애 때 없는 클라우드 앱으로 보낼 뿐이다. 앱 삭제는 성공이다
            }
        }
        return passthrough(removed);
    }

    /**
     * 앱 공개 주소 {app}.{플랫폼 존} 의 거점 (CNAME 내용물). home 은 CLOUD(ALB), ONPREM(에이전트 터널), NONE, OTHER.
     * 클라우드 앱을 내 PC 로 옮긴 뒤 에이전트가 주소를 터널로 바꿨는지 볼 때 쓴다
     */
    @GetMapping("/api/apps/{appName}/address")
    public ResponseEntity<?> address(@PathVariable String appName) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        if (!addresses.enabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                    .body(rejected("플랫폼 존이 설정되지 않았다"));
        }
        try {
            return ResponseEntity.ok(addresses.state(appName));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                    .body(rejected(e.getMessage()));
        }
    }

    /** 주소를 ALB 로 되돌린다 (내 PC 로 옮기다 실패했을 때). 본문 {"home":"cloud"}. 앱 레코드가 아니면 409 */
    @PutMapping("/api/apps/{appName}/address")
    public ResponseEntity<?> pointAddress(@PathVariable String appName,
                                          @RequestBody(required = false) AddressRequest request) {
        if (!APP_NAME.matcher(appName).matches() || request == null || !"cloud".equals(request.home())) {
            return ResponseEntity.badRequest().build();
        }
        if (!addresses.enabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                    .body(rejected("플랫폼 존이 설정되지 않았다"));
        }
        try {
            return ResponseEntity.ok(addresses.pointCloud(appName, clouds.originOf(appName)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                    .body(rejected(e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                    .body(rejected(e.getMessage()));
        }
    }

    public record AddressRequest(String home) {
    }

    /**
     * 거점과 배포 모드. ONPREM_ONLY 는 거점이 onprem 으로 고정이다.
     * HYBRID 는 에이전트가 보고한 home 이 있으면 그 값, 없으면 cloud.
     */
    @GetMapping("/api/apps/{appName}/home")
    public ResponseEntity<?> homeState(@PathVariable String appName) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        String mode = agents.deploymentMode(appName);
        String home = "ONPREM_ONLY".equals(mode) ? "onprem" : reportedHome(appName);
        return ResponseEntity.ok(Map.of("home", home, "deploymentMode", mode));
    }

    private String reportedHome(String appName) {
        return agents.burstState(appName)
                .map(burst -> burst.state() == null ? "" : burst.state().path("home").asText(""))
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase().startsWith("onprem") ? "onprem" : "cloud")
                .orElse("cloud");
    }

    /** 온프레미스 에이전트에 거점 전환을 보낸다. 최근 성공이 온프레미스가 아니면 409. 온프레미스 전용은 400 */
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
    /** 진행 중인 거점 전환 취소. 주소를 바꾸기 전 단계에서만 에이전트가 받는다 (반영은 다음 상태에서 보인다) */
    @PostMapping("/api/apps/{appName}/home/cancel")
    public ResponseEntity<?> cancelHome(@PathVariable String appName) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return agents.cancelHome(appName)
                    .<ResponseEntity<?>>map(state -> ResponseEntity.accepted().body(state))
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                    .body(rejected(e.getMessage()));
        }
    }

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
        return passthrough(clouds.cicdOf(appName).release(appName));
    }

    /** 스키마 이력(pgroll·Flyway)과 열린 pgroll 롤백 창. 프로젝트 상세의 스키마 이력 패널이 쓴다 */
    @GetMapping("/api/apps/{appName}/schema")
    public ResponseEntity<String> schema(@PathVariable String appName) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        // 온프레미스 앱이면 에이전트가 보낸 상태 (같은 모양)
        Optional<String> onprem = agents.schema(appName);
        if (onprem.isPresent()) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(onprem.get());
        }
        return passthrough(clouds.cicdOf(appName).schema(appName));
    }

    /** pgroll 롤백 창을 바로 닫는다 (complete). 이후에는 스키마를 되돌릴 수 없다. 열린 창이 없으면 409 */
    @PostMapping("/api/apps/{appName}/schema/complete")
    public ResponseEntity<String> completeSchema(@PathVariable String appName) {
        if (!APP_NAME.matcher(appName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            Optional<String> onprem = agents.completeSchema(appName);
            if (onprem.isPresent()) {
                return ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON).body(onprem.get());
            }
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(rejected(e.getMessage()));
        }
        return passthrough(clouds.cicdOf(appName).completeSchema(appName));
    }

    /** GCP 앱인데 GCP lily-cicd·주소가 연결되지 않았다. AWS 로 보내지 않는다 */
    @ExceptionHandler(CloudRouting.Unconfigured.class)
    public ResponseEntity<String> unconfigured(CloudRouting.Unconfigured e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                .body(rejected(e.getMessage()));
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
