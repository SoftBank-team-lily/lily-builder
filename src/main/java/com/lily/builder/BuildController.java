package com.lily.builder;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class BuildController {

    private final BuildService service;
    private final ClusterApps clusterApps;

    public BuildController(BuildService service, ClusterApps clusterApps) {
        this.service = service;
        this.clusterApps = clusterApps;
    }

    /** 빌드·배포는 몇 분 걸려서 바로 id 만 돌려준다. 진행 상황은 GET 으로 본다 */
    @PostMapping("/api/builds")
    public ResponseEntity<Build> start(@Valid @RequestBody BuildRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.start(request));
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
}
