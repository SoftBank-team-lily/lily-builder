package com.lily.builder.cloud;

import com.lily.builder.BuildRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
    private final CloudPlans plans;
    public CloudController(CloudService service, CloudWorkers workers, CloudRepository repository, CloudDispatches dispatches, CloudPlans plans) {
        this.service = service; this.workers = workers; this.repository = repository; this.dispatches = dispatches; this.plans = plans;
    }
    public record Deployment(@Valid @NotNull CloudPolicy.Request policy, @Valid @NotNull BuildRequest build) {}
    @PostMapping("/plans")
    public CloudPolicy.Decision plan(@Valid @RequestBody CloudPolicy.Request request) { return service.plan(request); }

    @PostMapping("/repository")
    public ResponseEntity<?> repository(@Valid @RequestBody Deployment request) {
        try {
            var evidence = repository.inspect(request.build());
            var policy = effective(request);
            var decision = service.plan(policy, evidence.facts());
            Map<String,Object> response = new LinkedHashMap<>(Map.of("repository",evidence,"decision",decision));
            if (decision.status().equals("selected") && !evidence.files().isEmpty()) {
                var plan = plans.create(request.build(),policy,evidence,decision);
                response.put("planId",plan.id()); response.put("expiresAt",plan.expiresAt());
            }
            return ResponseEntity.ok(response);
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
    public record Execution(
            @NotBlank @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
            @NotBlank @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String planId,
            @Valid @NotNull BuildRequest build) {}
    @PostMapping("/builds")
    public ResponseEntity<?> start(@Valid @RequestBody Execution request) {
        var build=request.build();
        if ("ONPREM_ONLY".equals(build.deploymentModeOrDefault()) || build.isStandby() || build.importsDatabase())
            return ResponseEntity.badRequest().body(Map.of("error","cloud_initial_deployment_only"));
        var result=dispatches.start(request.requestId(),request.planId(),build);
        return ResponseEntity.status(result.status()).body(result.body());
    }
    @GetMapping("/requests/{id:[0-9a-f-]{36}}")
    public Map<String,Object> request(@PathVariable String id) { return dispatches.request(id); }
    public record Reconcile(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{1,64}") String buildId) {}
    @PostMapping("/requests/{id:[0-9a-f-]{36}}/reconcile")
    public Map<String,Object> reconcile(@PathVariable String id, @Valid @RequestBody Reconcile request) {
        return dispatches.reconcile(id,request.buildId());
    }
    @ExceptionHandler(CloudPlans.Invalid.class)
    public ResponseEntity<?> invalid(CloudPlans.Invalid e) { return ResponseEntity.status(409).body(Map.of("error",e.getMessage())); }
    @ExceptionHandler(CloudState.Unavailable.class)
    public ResponseEntity<?> unavailable(CloudState.Unavailable e) { return ResponseEntity.status(503).body(Map.of("error",e.getMessage())); }
    @ExceptionHandler(CloudWorkers.Unavailable.class)
    public ResponseEntity<?> workerUnavailable(CloudWorkers.Unavailable e) { return ResponseEntity.status(502).body(Map.of("error",e.getMessage())); }
    @GetMapping("/builds/{provider:aws|gcp}/{id:[a-zA-Z0-9-]{1,64}}")
    public ResponseEntity<?> get(@PathVariable String provider, @PathVariable String id) {
        try { return ResponseEntity.ok(workers.get(provider, id)); }
        catch (CloudWorkers.Unavailable e) { return ResponseEntity.status(502).body(Map.of("error", e.getMessage())); }
    }
}
