package com.lily.builder.cloud;

import com.lily.jev.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class CloudPolicyTest {
    static final Instant NOW = Instant.parse("2026-10-03T05:00:00Z");
    static final Map<String,String> WORKERS = Map.of("aws","ap-northeast-2", "gcp","asia-northeast3");
    static CloudPolicy.Request request(String priority) {
        return new CloudPolicy.Request("auto", priority, "api-small", new BigDecimal("50"), 150,
            Set.of("ap-northeast-2", "asia-northeast3"), Set.of("container"));
    }
    static CloudPolicy.Candidate candidate(String provider, String cost, double p95, Instant at) {
        return new CloudPolicy.Candidate(provider, WORKERS.get(provider), "api-small", new BigDecimal(cost), p95,
            true, Set.of("container", "postgres", "pinned-source-v1"), at, "sample-1");
    }
    static List<CloudPolicy.Candidate> candidates() {
        return List.of(candidate("aws","40",80,NOW), candidate("gcp","30",120,NOW));
    }
    CloudPolicy.Decision decide(Jev jev, String priority, List<CloudPolicy.Candidate> candidates) {
        return new CloudPolicy(jev).decide(request(priority), candidates, WORKERS, NOW, 300);
    }
    @Test void unavailableModelUsesCostAndLatencyPolicies() {
        assertThat(decide(Jev.disabled(), "cost", candidates()).provider()).isEqualTo("gcp");
        assertThat(decide(Jev.disabled(), "latency", candidates()).provider()).isEqualTo("aws");
        assertThat(decide(Jev.disabled(), "balanced", candidates()).provider()).isEqualTo("aws");
    }
    @Test void sendsOnlyEligibleCandidatesAndUsesValidChoice() {
        Jev jev = (state, question) -> {
            assertThat(((Question.Choice) question).criteria()).containsOnlyKeys("aws", "gcp", "hold");
            assertThat(state.toString()).doesNotContain("http", "token");
            return Optional.of(new Answer("gcp", null, .9));
        };
        var result = decide(jev, "balanced", candidates());
        assertThat(result.provider()).isEqualTo("gcp");
        assertThat(result.source()).isEqualTo("jev");
        assertThat(result.selected().evidenceId()).isEqualTo("sample-1");
    }
    @Test void budgetAndLatencyAreEnforcedBeforeModel() {
        AtomicInteger calls = new AtomicInteger();
        var result = decide((s,q) -> { calls.incrementAndGet(); return Optional.empty(); }, "cost",
            List.of(candidate("aws","51",80,NOW), candidate("gcp","30",151,NOW)));
        assertThat(result.status()).isEqualTo("held");
        assertThat(calls.get()).isZero();
        assertThat(result.excluded()).extracting(CloudPolicy.Exclusion::reason).containsExactly("over_budget", "latency_exceeded");
    }
    @Test void unknownLowConfidenceAndNonFiniteAnswersUseRules() {
        for (Answer answer : List.of(new Answer("azure",null,1), new Answer("aws",null,.7),
                new Answer("aws",null,Double.NaN), new Answer("aws",null,1.1), new Answer("aws",.9,.9))) {
            var result = decide((s,q) -> Optional.of(answer), "cost", candidates());
            assertThat(result.provider()).isEqualTo("gcp");
            assertThat(result.source()).isEqualTo("rules");
        }
    }
    @Test void priorityCannotBeOverriddenByModel() {
        var result = decide((s,q) -> Optional.of(new Answer("aws",null,.99)), "cost", candidates());
        assertThat(result.provider()).isEqualTo("gcp");
        assertThat(result.reason()).isEqualTo("priority_enforced");
    }
    @Test void holdDoesNotDeploy() {
        assertThat(decide((s,q) -> Optional.of(new Answer("hold",null,.9)), "balanced", candidates()).status()).isEqualTo("held");
    }
    @Test void modelFailureUsesRules() {
        assertThat(decide((s,q) -> { throw new IllegalStateException("secret"); }, "cost", candidates()).provider()).isEqualTo("gcp");
    }
    @Test void balancedIsDefaultAndModelCannotChooseStrictlyWorseCandidate() {
        assertThat(request(null).priorityOrDefault()).isEqualTo("balanced");
        var result = decide((s,q) -> Optional.of(new Answer("gcp",null,.99)), "balanced",
            List.of(candidate("aws","20",50,NOW),candidate("gcp","40",100,NOW)));
        assertThat(result.provider()).isEqualTo("aws");
        assertThat(result.reason()).isEqualTo("dominated_choice");
    }
    @Test void modelStateWorksWithTheJevClientsPlainJsonMapper() {
        Jev jev = (state,question) -> {
            assertThatCode(() -> new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(state)).doesNotThrowAnyException();
            return Optional.of(new Answer("aws",null,.9));
        };
        assertThat(decide(jev,"balanced",candidates()).source()).isEqualTo("jev");
    }
    @Test void staleFutureAndMissingEvidenceAreRejected() {
        assertThat(decide(Jev.disabled(), "cost", List.of(candidate("aws","1",1,NOW.minusSeconds(301)),
                candidate("gcp","1",1,NOW.plusSeconds(31)))).status()).isEqualTo("held");
        assertThat(decide(Jev.disabled(), "cost", List.of(candidate("aws","1",Double.NaN,NOW))).status()).isEqualTo("held");
    }
    @Test void workerRegionAndProfileMustMatch() {
        var policy = new CloudPolicy(Jev.disabled());
        assertThat(policy.decide(request("cost"), candidates(), Map.of("aws","us-east-1"), NOW, 300).status()).isEqualTo("held");
        var c = new CloudPolicy.Candidate("aws", "ap-northeast-2", "other", new BigDecimal("1"), 10.,true,Set.of("container"), NOW,"sample");
        assertThat(policy.decide(request("cost"), List.of(c), WORKERS, NOW, 300).status()).isEqualTo("held");
    }
    @Test void explicitProviderNeverFallsBackToOtherCloud() {
        var r = request("cost");
        var explicit = new CloudPolicy.Request("gcp",r.priority(),r.profile(),r.maxMonthlyCostUsd(),r.maxP95Ms(),r.regions(),r.capabilities());
        assertThat(new CloudPolicy(Jev.disabled()).decide(explicit, candidates(), Map.of("aws","ap-northeast-2"),NOW,300).status()).isEqualTo("held");
    }
    @Test void duplicateAndUnsupportedCapabilitiesAreHeld() {
        assertThat(decide(Jev.disabled(),"cost",List.of(candidates().getFirst(),candidates().getFirst())).reason()).isEqualTo("duplicate_candidate");
        var r = request("cost");
        var mysql = new CloudPolicy.Request("auto",r.priority(),r.profile(),r.maxMonthlyCostUsd(),r.maxP95Ms(),r.regions(),Set.of("mysql"));
        assertThat(new CloudPolicy(Jev.disabled()).decide(mysql,candidates(),WORKERS,NOW,300).status()).isEqualTo("held");
    }
}
