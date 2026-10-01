package com.lily.builder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
