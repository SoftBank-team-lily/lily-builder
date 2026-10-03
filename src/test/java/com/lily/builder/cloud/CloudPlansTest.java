package com.lily.builder.cloud;

import com.lily.builder.BuildRequest;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudPlansTest {
    final MemoryCloudState state=new MemoryCloudState();
    final CloudWorkers workers=mock(CloudWorkers.class);
    final CloudService service=mock(CloudService.class);
    final CloudProperties props=CloudApiTest.props("","http://worker");
    final CloudPlans plans=new CloudPlans(state,props,CloudApiTest.JSON,Clock.fixed(CloudPolicyTest.NOW,ZoneOffset.UTC));
    final CloudDispatches dispatches=new CloudDispatches(state,workers,service,plans);
    BuildRequest build(String env) {
        return new BuildRequest("https://github.com/owner/app","main","repo-secret","backend","sample",8080,"postgres",null,null,Map.of("KEY",env));
    }
    CloudState.Plan plan(String env) {
        var policy=CloudPolicyTest.request("cost");
        var evidence=new CloudRepository.Evidence("a".repeat(40),"backend",List.of("pom.xml"),Map.of(),"portable","rules",null,List.of());
        var decision=new CloudPolicy(com.lily.jev.Jev.disabled()).decide(policy,CloudPolicyTest.candidates(),CloudPolicyTest.WORKERS,CloudPolicyTest.NOW,300);
        when(service.recheck(any(),any())).thenAnswer(i -> i.getArgument(1));
        return plans.create(build(env),policy,evidence,decision);
    }
    String id() { return UUID.randomUUID().toString(); }
    @Test void storesNoSecretsAndReusesTheExactDecisionAcrossServiceInstances() throws Exception {
        var p=plan("env-secret");
        assertThat(CloudApiTest.JSON.writeValueAsString(p)).doesNotContain("repo-secret","env-secret");
        var restarted=new CloudPlans(state,props,CloudApiTest.JSON,Clock.fixed(CloudPolicyTest.NOW,ZoneOffset.UTC));
        assertThat(restarted.require(p.id(),build("env-secret"))).isEqualTo(p);
        assertThatThrownBy(() -> restarted.require(p.id(),build("changed"))).hasMessage("plan_request_mismatch");
        var expired=new CloudPlans(state,props,CloudApiTest.JSON,Clock.fixed(CloudPolicyTest.NOW.plusSeconds(901),ZoneOffset.UTC));
        assertThatThrownBy(() -> expired.require(p.id(),build("env-secret"))).hasMessage("plan_expired");
    }
    @Test void retryReturnsOriginalBuildAndNewRequestCanRedeployAfterFailure() throws Exception {
        var p=plan("secret");
        var request=id();
        when(workers.start(any(),any())).thenReturn(CloudApiTest.JSON.readTree("{\"id\":\"first\",\"status\":\"QUEUED\"}"));
        when(workers.get("gcp","first")).thenReturn(CloudApiTest.JSON.readTree("{\"id\":\"first\",\"status\":\"FAILED\"}"));
        assertThat(dispatches.start(request,p.id(),build("secret")).status()).isEqualTo(202);
        assertThat(dispatches.start(request,p.id(),build("secret")).status()).isEqualTo(200);
        var second=plan("fixed");
        assertThat(dispatches.start(id(),second.id(),build("fixed")).status()).isEqualTo(202);
        verify(workers,times(2)).start(any(),any());
        verify(service,never()).plan(any(),anyMap());
        assertThatThrownBy(() -> dispatches.start(id(),p.id(),build("edited"))).hasMessage("plan_request_mismatch");
    }
    @Test void unfinishedBuildAndLostResponseNeverPermitAnotherDispatch() throws Exception {
        var p=plan("secret");
        var request=id();
        when(workers.start(any(),any())).thenThrow(new CloudWorkers.Unavailable("dispatch_unconfirmed"));
        assertThat(dispatches.start(request,p.id(),build("secret")).status()).isEqualTo(502);
        var restarted=new CloudDispatches(state,workers,service,plans);
        assertThat(restarted.start(request,p.id(),build("secret")).body()).containsEntry("error","dispatch_not_confirmed");
        assertThat(restarted.start(id(),p.id(),build("secret")).body()).containsEntry("error","build_in_progress");
        verify(workers,times(1)).start(any(),any());
    }
    @Test void rejectedRequestNeedsANewIdAndRecheckCanStopDispatch() throws Exception {
        var p=plan("secret");
        var request=id();
        when(workers.start(any(),any())).thenThrow(new CloudWorkers.Unavailable("dispatch_rejected"));
        dispatches.start(request,p.id(),build("secret"));
        assertThat(state.request(request).orElseThrow().status()).isEqualTo("REJECTED");
        dispatches.start(request,p.id(),build("secret"));
        verify(workers,times(1)).start(any(),any());
        when(service.recheck(any(),any())).thenReturn(CloudPolicy.held("changed_before_dispatch",List.of(),List.of(),CloudPolicyTest.NOW));
        assertThat(dispatches.start(id(),p.id(),build("secret")).status()).isEqualTo(409);
        verify(workers,times(1)).start(any(),any());
    }
    @Test void lostResponseCanBeReconciledOnlyWithMatchingAppAndCommit() throws Exception {
        var p=plan("secret"); var request=id();
        when(workers.start(any(),any())).thenThrow(new CloudWorkers.Unavailable("dispatch_unconfirmed"));
        dispatches.start(request,p.id(),build("secret"));
        when(workers.get("gcp","found")).thenReturn(CloudApiTest.JSON.readTree("{\"id\":\"found\",\"appName\":\"other\",\"commit\":\""+"a".repeat(40)+"\"}"));
        assertThatThrownBy(() -> dispatches.reconcile(request,"found")).hasMessage("worker_build_mismatch");
        when(workers.get("gcp","found")).thenReturn(CloudApiTest.JSON.readTree("{\"id\":\"found\",\"appName\":\"sample\",\"commit\":\""+"a".repeat(40)+"\"}"));
        var matching=CloudApiTest.JSON.createObjectNode().put("id","found").put("appName","sample")
            .put("commit","a".repeat(40)).put("createdAt",java.time.Instant.now().toString());
        when(workers.get("gcp","found")).thenReturn(matching);
        assertThat(dispatches.reconcile(request,"found")).containsEntry("buildId","found");
    }
}
