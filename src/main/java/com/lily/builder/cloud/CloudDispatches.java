package com.lily.builder.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.lily.builder.BuildRequest;
import org.springframework.stereotype.Component;
import java.util.*;

/** 요청별 idempotency와 앱별 진행 상태를 영속 기록한다. 불확실한 실행은 자동 해제하지 않는다. */
@Component
public class CloudDispatches {
    private final CloudState state;
    private final CloudWorkers workers;
    private final CloudService service;
    private final CloudPlans plans;
    public CloudDispatches(CloudState state, CloudWorkers workers, CloudService service, CloudPlans plans) {
        this.state=state; this.workers=workers; this.service=service; this.plans=plans;
    }
    public record Result(int status, Map<String,Object> body) {}
    public Result start(String requestId, String planId, BuildRequest build) {
        var existing=state.request(requestId);
        if (existing.isPresent()) return replay(existing.get(),planId);
        var plan=plans.require(planId,build);
        var checked=service.recheck(plan.policy(),plan.decision());
        if (!checked.status().equals("selected")) return new Result(409,Map.of("planId",planId,"decision",checked));
        var previous=state.app(plan.appName()).orElse(null);
        if (previous != null) {
            previous=refresh(previous);
            if (!previous.terminal()) return new Result(409,body(previous,"build_in_progress"));
            if (!previous.status().equals("REJECTED") && !previous.provider().equals(checked.provider()))
                return new Result(409,body(previous,"provider_change_requires_migration"));
        }
        var run=new CloudState.Run(requestId,planId,plan.appName(),checked.provider(),null,"DISPATCHING",plan.repository().commit(),java.time.Instant.now());
        if (!state.claim(run,previous)) {
            var raced=state.request(requestId);
            return raced.map(r -> replay(r,planId)).orElseGet(() -> new Result(409,Map.of("error","build_state_changed")));
        }
        JsonNode accepted;
        try { accepted=workers.start(run.provider(),build.withCommit(plan.repository().commit())); }
        catch (CloudWorkers.Unavailable e) {
            if ("dispatch_rejected".equals(e.getMessage())) state.update(run,run.with(null,"REJECTED"));
            return new Result(502,Map.of("error",e.getMessage(),"requestId",requestId,"planId",planId,"retryable",false));
        }
        var started=run.with(accepted.path("id").asText(),"STARTED");
        if (!state.update(run,started)) throw new CloudState.Unavailable();
        var response=body(started,null);
        response.put("decision",checked); response.put("repository",plan.repository()); response.put("build",accepted);
        return new Result(202,response);
    }
    public Map<String,Object> request(String requestId) {
        var run=state.request(requestId).orElseThrow(() -> new CloudPlans.Invalid("request_not_found"));
        return body(refresh(run),null);
    }
    /** 접수 응답을 잃었을 때 운영자가 worker에서 확인한 ID를 연결한다. 앱/커밋이 일치해야 한다. */
    public Map<String,Object> reconcile(String requestId, String buildId) {
        var run=state.request(requestId).orElseThrow(() -> new CloudPlans.Invalid("request_not_found"));
        if (!run.status().equals("DISPATCHING")) throw new CloudPlans.Invalid("request_not_pending");
        var build=workers.get(run.provider(),buildId);
        if (!run.appName().equals(build.path("appName").asText()) || !run.commit().equals(build.path("commit").asText()))
            throw new CloudPlans.Invalid("worker_build_mismatch");
        try {
            if (java.time.Instant.parse(build.path("createdAt").asText()).isBefore(run.submittedAt()))
                throw new CloudPlans.Invalid("worker_build_mismatch");
        } catch (java.time.format.DateTimeParseException e) { throw new CloudPlans.Invalid("worker_build_mismatch"); }
        var started=run.with(buildId,"STARTED");
        if (!state.update(run,started)) throw new CloudPlans.Invalid("build_state_changed");
        return body(started,null);
    }
    private Result replay(CloudState.Run run, String planId) {
        if (!run.planId().equals(planId)) return new Result(409,Map.of("error","request_id_conflict"));
        run=refresh(run);
        return new Result(run.buildId() == null ? 409 : 200,body(run,run.buildId() == null ? "dispatch_not_confirmed" : null));
    }
    private CloudState.Run refresh(CloudState.Run run) {
        if (run.terminal() || run.buildId() == null) return run;
        var current=workers.get(run.provider(),run.buildId());
        String status=current.path("status").asText();
        if (Set.of("SUCCEEDED","FAILED","ROLLED_BACK","CANCELLED").contains(status)) {
            var finished=run.with(run.buildId(),status);
            if (state.update(run,finished)) return finished;
            return state.request(run.requestId()).orElseThrow(CloudState.Unavailable::new);
        }
        return run;
    }
    private static Map<String,Object> body(CloudState.Run run, String error) {
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("appName",run.appName()); body.put("commit",run.commit());
        body.put("requestId",run.requestId()); body.put("planId",run.planId()); body.put("provider",run.provider()); body.put("status",run.status());
        if (run.buildId()!=null) { body.put("buildId",run.buildId()); body.put("statusPath","/api/cloud/builds/"+run.provider()+"/"+run.buildId()); }
        if (error!=null) body.put("error",error);
        return body;
    }
}
