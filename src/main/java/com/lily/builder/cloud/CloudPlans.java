package com.lily.builder.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lily.builder.BuildRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

@Component
public class CloudPlans {
    private final CloudState state;
    private final CloudProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    @Autowired
    public CloudPlans(CloudState state, CloudProperties props, ObjectMapper json) {
        this(state,props,json,Clock.systemUTC());
    }
    CloudPlans(CloudState state, CloudProperties props, ObjectMapper json, Clock clock) {
        this.state=state; this.props=props; this.json=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS); this.clock=clock;
    }
    public CloudState.Plan create(BuildRequest build, CloudPolicy.Request policy, CloudRepository.Evidence evidence, CloudPolicy.Decision decision) {
        var plan=new CloudState.Plan(UUID.randomUUID().toString(),build.appName(),fingerprint(build,evidence.commit(),decision.provider()),
            policy,evidence,decision,clock.instant().plusSeconds(900));
        state.savePlan(plan);
        return plan;
    }
    public CloudState.Plan require(String id, BuildRequest build) {
        var plan=state.plan(id).orElseThrow(() -> new Invalid("plan_not_found"));
        if (!plan.expiresAt().isAfter(clock.instant())) throw new Invalid("plan_expired");
        String actual=fingerprint(build,plan.repository().commit(),plan.decision().provider());
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),plan.fingerprint().getBytes(StandardCharsets.US_ASCII)))
            throw new Invalid("plan_request_mismatch");
        return plan;
    }
    private String fingerprint(BuildRequest build, String commit, String provider) {
        try {
            if (props.apiToken() == null || props.apiToken().length() < 32) throw new CloudState.Unavailable();
            if (build.sourceCommit() != null && !build.sourceCommit().equals(commit)) throw new Invalid("plan_request_mismatch");
            ObjectNode tree=json.valueToTree(build.withCommit(commit));
            tree.remove("token");
            if (!CloudProperties.usable(props.worker(provider))) throw new Invalid("worker_changed");
            tree.put("worker",props.worker(provider).url());
            Object canonical=json.convertValue(tree,Object.class);
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.apiToken().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(json.writeValueAsBytes(canonical)));
        } catch (Invalid e) { throw e; }
        catch (Exception e) { throw new CloudState.Unavailable(); }
    }
    public static final class Invalid extends RuntimeException { public Invalid(String reason) { super(reason); } }
}
