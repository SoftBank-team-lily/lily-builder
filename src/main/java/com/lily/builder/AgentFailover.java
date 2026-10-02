package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 온프레미스 PC 장애 시 공개 주소를 클라우드로 돌린다.
 *
 * <p>공개 주소({app}.{zone})는 평소 PC 터널을 가리키고, 버스팅도 PC 프록시가 넘긴다. 그래서 PC 가 꺼지면 클라우드
 * 대기 Pod 가 떠 있어도 서비스가 죽는다. 에이전트 연결이 끊긴 채 유예 시간이 지나면 builder 가 CNAME 을 ALB 로 바꾼다.
 *
 * <pre>
 * 대상: 끊기기 직전 burst-state 가 버스팅 켜짐 · 대기 Pod 준비됨(warm) · 거점 ONPREM · DB 가 PC 에 없음(cloud 또는 없음)
 * 순서: 클라우드 레플리카를 서비스 수로 → Ready 확인 → 에이전트가 아직 끊겨 있으면 CNAME 을 ALB 로
 * 복귀: 에이전트가 다시 붙으면 CNAME 을 읽어 거점을 클라우드로 안다. 사용자가 "내 PC 로 되돌리기"로 옮긴다
 * </pre>
 */
@Component
public class AgentFailover {

    private static final Logger log = LoggerFactory.getLogger(AgentFailover.class);
    private static final Set<String> LOCAL_DATABASES = Set.of("local", "external");

    private final AgentHub hub;
    private final AgentDeployService deploys;
    private final CicdClient cicd;
    private final AppAddress addresses;
    private final Duration grace;
    private final int replicas;
    private final Duration readyTimeout;
    private final Supplier<Instant> clock;
    private final Runner runner;
    /** 끊김 하나에 한 번만 판단한다. key → 처리한 끊김 시각 */
    private final Map<String, Instant> handled = new ConcurrentHashMap<>();

    @Autowired
    public AgentFailover(AgentHub hub, AgentDeployService deploys, CicdClient cicd, AppAddress addresses,
                         @Value("${lily.builder.failover.grace-seconds:60}") int graceSeconds,
                         @Value("${lily.builder.failover.replicas:2}") int replicas) {
        this(hub, deploys, cicd, addresses, Duration.ofSeconds(graceSeconds), replicas, Duration.ofSeconds(120),
                Instant::now, task -> Thread.ofVirtual().name("lily-failover").start(task));
    }

    AgentFailover(AgentHub hub, AgentDeployService deploys, CicdClient cicd, AppAddress addresses, Duration grace,
                  int replicas, Duration readyTimeout, Supplier<Instant> clock, Runner runner) {
        this.hub = hub;
        this.deploys = deploys;
        this.cicd = cicd;
        this.addresses = addresses;
        this.grace = grace;
        this.replicas = replicas;
        this.readyTimeout = readyTimeout;
        this.clock = clock;
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "#{${lily.builder.failover.check-seconds:5} * 1000}")
    void check() {
        if (grace.isZero() || grace.isNegative() || !addresses.enabled()) {
            return;
        }
        Instant now = clock.get();
        hub.disconnectedSince().forEach((key, since) -> {
            if (Duration.between(since, now).compareTo(grace) < 0 || since.equals(handled.get(key))) {
                return;
            }
            handled.put(key, since);
            JsonNode state = hub.lastState(key);
            Optional<String> skip = skip(key, state);
            if (skip.isPresent()) {
                log.info("failover skipped: key={} reason={}", key, skip.get());
                return;
            }
            String app = state.path("app").asText();
            runner.run(() -> failover(key, app));
        });
    }

    /** @return 자동 전환하지 않는 이유. 전환 대상이면 비어 있다 */
    Optional<String> skip(String key, JsonNode state) {
        if (state == null) {
            return Optional.of("끊기기 전 상태가 없다");
        }
        String app = state.path("app").asText("");
        if (app.isBlank()) {
            return Optional.of("다루던 앱이 없다");
        }
        if (!state.path("enabled").asBoolean(false) || !state.path("warm").asBoolean(false)) {
            return Optional.of("버스팅 대기 Pod 가 없다");
        }
        if (!"ONPREM".equals(state.path("home").asText())) {
            return Optional.of("거점이 내 PC 가 아니다: " + state.path("home").asText());
        }
        if (LOCAL_DATABASES.contains(state.path("databaseMode").asText(""))) {
            return Optional.of("DB 가 PC 쪽에 있다");
        }
        if (!deploys.ownedBy(key, app)) {
            return Optional.of(app + " 은 이 에이전트의 앱이 아니다");
        }
        return Optional.empty();
    }

    void failover(String key, String app) {
        try {
            cicd.scale(app, replicas);
            if (!awaitReady(app)) {
                log.warn("failover aborted: app={} cloud pod not ready", app);
                return;
            }
            if (hub.connected(key)) {
                // 기다리는 동안 PC 가 돌아왔다. 주소는 그대로 두고 대기 수로 되돌린다
                cicd.scale(app, 1);
                log.info("failover cancelled: app={} agent reconnected", app);
                return;
            }
            AppAddress.State state = addresses.state(app);
            if (state.home() != AppAddress.Home.ONPREM) {
                log.info("failover skipped: app={} address is {}", app, state.home());
                return;
            }
            addresses.pointCloud(app);
            log.warn("failover: app={} agent {} disconnected, address {} now points to cloud", app, key, state.host());
        } catch (RuntimeException e) {
            log.warn("failover failed: app={} message={}", app, e.getMessage());
        }
    }

    private boolean awaitReady(String app) {
        Instant deadline = clock.get().plus(readyTimeout);
        while (true) {
            CicdClient.AppStatus status = cicd.status(app);
            if (status != null && status.readyReplicas() >= 1) {
                return true;
            }
            if (!clock.get().isBefore(deadline)) {
                return false;
            }
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    @FunctionalInterface
    interface Runner {
        void run(Runnable task);
    }
}
