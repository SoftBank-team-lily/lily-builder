package com.lily.builder.cloud;

import java.time.Instant;
import java.util.Optional;

/** 계획과 배포 요청/앱 상태를 영속 보관한다. 실행 기록에는 자동 만료를 적용하지 않는다. */
public interface CloudState {
    record Plan(String id, String appName, String fingerprint, CloudPolicy.Request policy,
                CloudRepository.Evidence repository, CloudPolicy.Decision decision, Instant expiresAt) {}
    record Run(String requestId, String planId, String appName, String provider, String buildId, String status, String commit, Instant submittedAt) {
        public boolean terminal() { return java.util.Set.of("SUCCEEDED","FAILED","ROLLED_BACK","CANCELLED","REJECTED").contains(status); }
        public Run with(String id, String state) { return new Run(requestId,planId,appName,provider,id,state,commit,submittedAt); }
    }
    void savePlan(Plan plan);
    Optional<Plan> plan(String id);
    Optional<Run> request(String requestId);
    Optional<Run> app(String appName);
    /** 요청 ID가 새롭고 앱 상태가 previous와 같을 때에만 원자적으로 두 항목을 기록한다. */
    boolean claim(Run next, Run previous);
    /** 요청과 앱 상태를 함께 비교 후 변경한다. */
    boolean update(Run previous, Run next);
    final class Unavailable extends RuntimeException {
        public Unavailable() { super("cloud_state_unavailable"); }
    }
}
