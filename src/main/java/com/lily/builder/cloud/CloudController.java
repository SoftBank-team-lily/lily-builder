package com.lily.builder.cloud;

import com.lily.builder.BuildRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/cloud")
public class CloudController {
    private final CloudService service;
    private final CloudWorkers workers;
    private final CloudRepository repository;
    private final CloudDispatches dispatches;
    public CloudController(CloudService service, CloudWorkers workers, CloudRepository repository, CloudDispatches dispatches) {
        this.service = service; this.workers = workers; this.repository = repository; this.dispatches = dispatches;
    }
    public record Deployment(@Valid @NotNull CloudPolicy.Request policy, @Valid @NotNull BuildRequest build) {}
    @PostMapping("/plans")
    public CloudPolicy.Decision plan(@Valid @RequestBody CloudPolicy.Request request) { return service.plan(request); }

    @PostMapping("/repository")
    public ResponseEntity<?> repository(@Valid @RequestBody Deployment request) {
        try {
            var evidence = repository.inspect(request.build());
            return ResponseEntity.ok(Map.of("repository", evidence, "decision", service.plan(effective(request), evidence.facts())));
        } catch (CloudRepository.Unavailable e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        }
    }
    private CloudPolicy.Request effective(Deployment request) {
        BuildRequest build = request.build();
        Set<String> capabilities = new HashSet<>(request.policy().required());
        capabilities.add("container");
        capabilities.add("pinned-source-v1");
        if ("auto".equals(build.database())) capabilities.addAll(Set.of("postgres", "mysql"));
        else if (build.database() != null && !build.database().isBlank()) capabilities.add(build.database());
        if (build.givenDatabase() || "external".equals(build.databaseMode())) capabilities.add("external-db");
        var p = request.policy();
        return new CloudPolicy.Request(p.provider(), p.priority(), p.profile(), p.maxMonthlyCostUsd(), p.maxP95Ms(), p.regions(), capabilities, p.context());
    }
    @PostMapping("/builds")
    public ResponseEntity<?> start(@Valid @RequestBody Deployment request) {
        BuildRequest build = request.build();
        // 기존 앱의 거점/DB 전환에는 별도의 이전 절차가 필요하다.
        if ("ONPREM_ONLY".equals(build.deploymentModeOrDefault()) || build.isStandby() || build.importsDatabase())
            return ResponseEntity.badRequest().body(Map.of("error", "cloud_initial_deployment_only"));
        CloudRepository.Evidence evidence;
        try { evidence = repository.inspect(build); }
        catch (CloudRepository.Unavailable e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        }
        if (evidence.files().isEmpty())
            return ResponseEntity.status(409).body(Map.of("error", "repository_manifest_required", "repository", evidence));
        build = build.withCommit(evidence.commit());
        var effective = effective(request);
        var decision = service.plan(effective, evidence.facts());
        if (decision.status().equals("selected")) decision = service.recheck(effective, decision);
        if (!decision.status().equals("selected")) return ResponseEntity.status(409).body(Map.of("decision", decision, "repository", evidence));
        // 같은 앱을 방금 보냈으면 다시 보내지 않고 그 배포를 알려 준다
        var previous = dispatches.claim(build.appName(), decision.provider());
        if (previous.isPresent()) {
            Map<String,Object> body = new LinkedHashMap<>(Map.of("error", "build_already_started", "appName", build.appName(),
                "provider", previous.get().provider()));
            if (previous.get().buildId() != null)
                body.put("statusPath", "/api/cloud/builds/" + previous.get().provider() + "/" + previous.get().buildId());
            return ResponseEntity.status(409).body(body);
        }
        try {
            var buildResult = workers.start(decision.provider(), build);
            String id = buildResult.path("id").asText();
            dispatches.started(build.appName(), decision.provider(), id);
            return ResponseEntity.accepted().body(Map.of("decision", decision, "repository", evidence, "build", buildResult,
                "statusPath", "/api/cloud/builds/" + decision.provider() + "/" + id));
        } catch (CloudWorkers.Unavailable e) {
            // 거절이 확실하면 다시 보낼 수 있다. 그 밖에는 접수됐을 가능성이 있어 다시 보내거나 다른 클라우드로 보내지 않는다.
            boolean rejected = "dispatch_rejected".equals(e.getMessage());
            if (rejected) dispatches.release(build.appName());
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage(), "decision", decision,
                "appName", build.appName(), "retryable", rejected));
        }
    }
    @GetMapping("/builds/{provider:aws|gcp}/{id:[a-zA-Z0-9-]{1,64}}")
    public ResponseEntity<?> get(@PathVariable String provider, @PathVariable String id) {
        try { return ResponseEntity.ok(workers.get(provider, id)); }
        catch (CloudWorkers.Unavailable e) { return ResponseEntity.status(502).body(Map.of("error", e.getMessage())); }
    }
}
