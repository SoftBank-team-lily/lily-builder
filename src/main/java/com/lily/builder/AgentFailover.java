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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 온프레미스 PC 장애 시 공개 주소를 클라우드로 돌린다.
 *
 * <p>공개 주소({app}.{zone})는 평소 PC 터널을 가리키고, 버스팅도 PC 프록시가 넘긴다. 그래서 PC 가 꺼지면 클라우드
 * 대기 Pod 가 떠 있어도 서비스가 죽는다. 에이전트 연결이 끊긴 채 유예 시간이 지나면 builder 가 CNAME 을 ALB 로 바꾼다.
 *
 * <pre>
 * 대상: 끊기기 직전 burst-state 가 버스팅 켜짐 · 대기 Pod 준비됨(warm) · 거점 ONPREM · DB 가 사용자 DB(external)가 아님
 * DB 가 PC 에 없음(cloud 또는 없음):
 *   순서: 클라우드 레플리카를 서비스 수로 → Ready 확인 → 에이전트가 아직 끊겨 있으면 CNAME 을 ALB 로
 *   복귀: 에이전트가 다시 붙으면 CNAME 을 읽어 거점을 클라우드로 안다. 사용자가 "내 PC 로 되돌리기"로 옮긴다
 * DB 가 PC(local): 대기 Pod 는 평소 PC DB 를 역방향 터널로 쓴다. PC 가 죽으면 그 DB 도 없다
 *   순서: 에이전트가 정기 백업한 클라우드 사본(provisioner 의 같은 앱 DB)으로 대기 Pod 의 DB 를 바꾼다 → 레플리카를 서비스 수로
 *   주소(CNAME)는 그대로 둔다. 엣지 Worker 가 PC 가 못 받은 GET 을 대기 Pod 로 보내 사본으로 읽고,
 *   POST 는 쓰기 큐에 쌓였다가 PC 가 돌아오면 PC 로 다시 간다 (사본에 쓴 것은 PC 로 돌아가지 않으므로 주소를 넘기지 않는다)
 *   복귀: 에이전트가 다시 붙으면 대기 Pod 의 DB 를 역방향 터널로 되돌리고 대기 수로 내린다
 * </pre>
 */
@Component
public class AgentFailover {

    private static final Logger log = LoggerFactory.getLogger(AgentFailover.class);
    /** 사용자가 준 DB. 사본이 없어 건드리지 않는다 */
    private static final String EXTERNAL_DATABASE = "external";
    /** 내 PC DB. 장애 때 대기 Pod 를 클라우드 사본으로 읽게 한다 */
    private static final String LOCAL_DATABASE = "local";
    /** 대기 수 (failover 취소와 같은 값) */
    private static final int WARM_REPLICAS = 1;

    private final AgentHub hub;
    private final AgentDeployService deploys;
    private final CicdClient cicd;
    private final CloudClients clouds;
    private final AppAddress addresses;
    private final ProvisionerClient provisioner;
    private final Duration grace;
    private final int replicas;
    private final Duration readyTimeout;
    private final Supplier<Instant> clock;
    private final Runner runner;
    /** 끊김 하나에 한 번만 판단한다. key → 처리한 끊김 시각 */
    private final Map<String, Instant> handled = new ConcurrentHashMap<>();
    /** 사본으로 돌린 앱. key → app. 에이전트가 다시 붙으면 되돌린다 (builder 메모리. 재시작하면 cicd DELETE 로 되돌린다) */
    private final Map<String, String> readingCopy = new ConcurrentHashMap<>();

    @Autowired
    public AgentFailover(AgentHub hub, AgentDeployService deploys, CicdClient cicd, AppAddress addresses,
                         @Value("${lily.builder.failover.grace-seconds:60}") int graceSeconds,
                         @Value("${lily.builder.failover.replicas:2}") int replicas, CloudClients clouds,
                         ProvisionerClient provisioner) {
        this(hub, deploys, cicd, addresses, Duration.ofSeconds(graceSeconds), replicas, Duration.ofSeconds(120),
                Instant::now, task -> Thread.ofVirtual().name("lily-failover").start(task), clouds, provisioner);
    }

    AgentFailover(AgentHub hub, AgentDeployService deploys, CicdClient cicd, AppAddress addresses, Duration grace,
                  int replicas, Duration readyTimeout, Supplier<Instant> clock, Runner runner) {
        this(hub, deploys, cicd, addresses, grace, replicas, readyTimeout, clock, runner, null, null);
    }

    AgentFailover(AgentHub hub, AgentDeployService deploys, CicdClient cicd, AppAddress addresses, Duration grace,
                  int replicas, Duration readyTimeout, Supplier<Instant> clock, Runner runner, CloudClients clouds,
                  ProvisionerClient provisioner) {
        this.hub = hub;
        this.deploys = deploys;
        this.cicd = cicd;
        this.clouds = clouds;
        this.addresses = addresses;
        this.provisioner = provisioner;
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
        readingCopy.forEach((key, app) -> {
            if (hub.connected(key) && readingCopy.remove(key, app)) {
                runner.run(() -> restoreCopy(key, app));
            }
        });
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
            if (LOCAL_DATABASE.equals(state.path("databaseMode").asText(""))) {
                runner.run(() -> readCopy(key, app));
            } else {
                runner.run(() -> failover(key, app));
            }
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
        if (EXTERNAL_DATABASE.equals(state.path("databaseMode").asText(""))) {
            return Optional.of("DB 가 사용자 DB 라 클라우드 사본이 없다");
        }
        if (!deploys.ownedBy(key, app)) {
            return Optional.of(app + " 은 이 에이전트의 앱이 아니다");
        }
        return Optional.empty();
    }

    void failover(String key, String app) {
        try {
            boolean gcp = "GCP".equals(deploys.cloudProvider(app));
            CicdClient target = cicd;
            String origin = null;
            if (gcp) {
                if (clouds == null || !clouds.deployConfigured() || clouds.origin().isBlank()) {
                    log.warn("failover aborted: app={} GCP cloud is not configured", app);
                    return;
                }
                target = clouds.cicd();
                origin = clouds.origin();
            }
            target.scale(app, replicas);
            if (!awaitReady(app, target)) {
                log.warn("failover aborted: app={} cloud pod not ready", app);
                return;
            }
            if (hub.connected(key)) {
                // 기다리는 동안 PC 가 돌아왔다. 주소는 그대로 두고 대기 수로 되돌린다
                target.scale(app, 1);
                log.info("failover cancelled: app={} agent reconnected", app);
                return;
            }
            AppAddress.State state = addresses.state(app);
            if (state.home() != AppAddress.Home.ONPREM) {
                log.info("failover skipped: app={} address is {}", app, state.home());
                return;
            }
            if (origin == null) {
                addresses.pointCloud(app);
            } else {
                addresses.pointCloud(app, origin);
            }
            log.warn("failover: app={} agent {} disconnected, address {} now points to cloud", app, key, state.host());
        } catch (RuntimeException e) {
            log.warn("failover failed: app={} message={}", app, e.getMessage());
        }
    }

    /**
     * DB 가 PC 인 앱: 대기 Pod 를 클라우드 사본(에이전트가 정기 백업한 DB)으로 읽게 하고 레플리카를 올린다. 주소는 두고,
     * 사본이 없으면(한 번도 백업하지 않았으면) 하지 않는다. GCP 앱은 아직 하지 않는다.
     */
    void readCopy(String key, String app) {
        try {
            if ("GCP".equals(deploys.cloudProvider(app))) {
                log.info("failover skipped: app={} reading the cloud copy is AWS only for now", app);
                return;
            }
            if (provisioner == null) {
                log.info("failover skipped: app={} provisioner is not configured", app);
                return;
            }
            Optional<ProvisionerClient.Connection> copy = provisioner.existing(app, null, null);
            if (copy.isEmpty() || copy.get().env().isEmpty()) {
                log.info("failover skipped: app={} no cloud copy yet (the agent has not backed up)", app);
                return;
            }
            cicd.switchDatabase(app, copy.get().env());
            readingCopy.put(key, app);
            cicd.scale(app, replicas);
            if (!awaitReady(app, cicd)) {
                log.warn("failover: app={} reads the cloud copy but no pod is ready yet", app);
            }
            if (hub.connected(key) && readingCopy.remove(key, app)) {
                // 기다리는 동안 PC 가 돌아왔다
                restoreCopy(key, app);
                log.info("failover cancelled: app={} agent reconnected", app);
                return;
            }
            log.warn("failover: app={} agent {} disconnected, standby now reads the cloud copy (address unchanged)",
                    app, key);
        } catch (RuntimeException e) {
            log.warn("failover failed: app={} message={}", app, e.getMessage());
        }
    }

    /** PC 가 돌아왔다. 대기 Pod 의 DB 를 PC(역방향 터널)로 되돌리고 대기 수로 내린다 */
    void restoreCopy(String key, String app) {
        try {
            cicd.restoreDatabase(app);
            cicd.scale(app, WARM_REPLICAS);
            log.info("failover restored: app={} agent {} reconnected, standby reads the PC database again", app, key);
        } catch (RuntimeException e) {
            log.warn("failover restore failed: app={} message={}", app, e.getMessage());
        }
    }

    private boolean awaitReady(String app, CicdClient client) {
        Instant deadline = clock.get().plus(readyTimeout);
        while (true) {
            CicdClient.AppStatus status = client.status(app);
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
