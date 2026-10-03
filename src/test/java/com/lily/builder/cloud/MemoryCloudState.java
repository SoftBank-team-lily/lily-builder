package com.lily.builder.cloud;

import java.util.*;

/** 테스트에서만 사용하는 영속 저장소 계약 구현. 운영에는 메모리 대체 경로가 없다. */
final class MemoryCloudState implements CloudState {
    final Map<String,Plan> plans=new HashMap<>();
    final Map<String,Run> requests=new HashMap<>();
    final Map<String,Run> apps=new HashMap<>();
    public synchronized void savePlan(Plan plan) { plans.put(plan.id(),plan); }
    public synchronized Optional<Plan> plan(String id) { return Optional.ofNullable(plans.get(id)); }
    public synchronized Optional<Run> request(String id) { return Optional.ofNullable(requests.get(id)); }
    public synchronized Optional<Run> app(String name) { return Optional.ofNullable(apps.get(name)); }
    public synchronized boolean claim(Run next, Run previous) {
        if (requests.containsKey(next.requestId()) || !Objects.equals(apps.get(next.appName()),previous)) return false;
        requests.put(next.requestId(),next); apps.put(next.appName(),next); return true;
    }
    public synchronized boolean update(Run previous, Run next) {
        if (!Objects.equals(requests.get(previous.requestId()),previous) || !Objects.equals(apps.get(previous.appName()),previous)) return false;
        requests.put(next.requestId(),next); apps.put(next.appName(),next); return true;
    }
}
