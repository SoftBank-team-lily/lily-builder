package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 클라우드 전용 앱을 다른 클라우드로 옮긴다 (AWS ↔ GCP). DB(PostgreSQL)도 복사한다.
 *
 * <ol>
 *   <li>PREPARE: 옮길 클라우드 레지스트리에 이미지를 미리 만든다 (다운타임 전)</li>
 *   <li>DATABASE: 옮길 클라우드 provisioner 로 빈 DB 를 만든다</li>
 *   <li>FREEZE: 원본 앱을 내린다 (replicas 0). 여기서부터 다운타임</li>
 *   <li>COPY: 원본 DB → 새 DB ({@link DatabaseCopy})</li>
 *   <li>DEPLOY: 만들어 둔 이미지로 새 클라우드에 배포한다</li>
 *   <li>SWITCH: 공개 주소 CNAME 을 새 클라우드로 바꾸고 공개 주소로 확인한다</li>
 * </ol>
 * 어느 단계에서 실패하든 원본을 다시 올리고 주소를 되돌린 뒤 새 쪽을 지운다. 끝나면 HOLD: 원본은 replicas 0 과
 * DB 를 그대로 두고, 사용자가 되돌리거나({@link #rollback}) 정리한다({@link #finalizeMigration}).
 *
 * <p>상태는 배포 이력 저장소에 빌드 한 건(옮기기 기록)으로 남긴다. 기록은 옮기기 전 클라우드를
 * {@code cloudProvider=} 로 적고, 전환을 마치면 새 클라우드를 덧붙인다 ({@link AgentDeployService#cloudProvider} 는 마지막 줄을 본다).
 * 옮기는 중에 만든 빌드에는 {@link #PART} 를 남겨 앱의 클라우드 판정에서 뺀다.
 */
@Service
public class AppMigration {

    private static final Logger log = LoggerFactory.getLogger(AppMigration.class);

    /** 옮기는 중에 만든 빌드 (이미지, 새 클라우드 배포). 뒤에 옮기기 기록 id */
    public static final String PART = "migrate: part ";
    static final String RECORD = "migrate: record ";
    static final String STEP = "migrate: step ";
    static final String COMMITTED = "migrate: committed";
    static final String FINALIZED = "migrate: finalized";
    static final String ROLLED_BACK = "migrate: rolled back";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<Build.Status> RUNNING = Set.of(Build.Status.QUEUED, Build.Status.BUILDING,
            Build.Status.DEPLOYING);

    public enum Step { PREPARE, DATABASE, FREEZE, COPY, DEPLOY, SWITCH }

    /** 공개 주소를 한 번 불러 상태 코드를 돌려준다. 연결이 안 되면 -1 */
    @FunctionalInterface
    interface Probe {
        int status(String url);
    }

    private final BuildStore store;
    private final BuildService builds;
    private final AgentDeployService deploys;
    private final CicdClient awsCicd;
    private final ProvisionerClient awsProvisioner;
    private final CloudClients clouds;
    private final AppAddress addresses;
    private final DatabaseCopy copy;
    private final TunnelCertificates certificates;
    private final BuildService.BuildRunner runner;
    private final Probe probe;
    private final Duration verifyTimeout;
    private final Duration waitStep;
    /** 지금 옮기고 있는 앱. 같은 앱을 두 번 옮기지 않는다 */
    private final Set<String> moving = ConcurrentHashMap.newKeySet();

    @Autowired
    public AppMigration(BuildStore store, BuildService builds, AgentDeployService deploys, CicdClient cicd,
                        ProvisionerClient provisioner, CloudClients clouds, AppAddress addresses, DatabaseCopy copy,
                        TunnelCertificates certificates, BuildService.BuildRunner runner) {
        this(store, builds, deploys, cicd, provisioner, clouds, addresses, copy, certificates, runner,
                AppMigration::httpStatus, Duration.ofSeconds(90), Duration.ofSeconds(3));
    }

    AppMigration(BuildStore store, BuildService builds, AgentDeployService deploys, CicdClient cicd,
                 ProvisionerClient provisioner, CloudClients clouds, AppAddress addresses, DatabaseCopy copy,
                 TunnelCertificates certificates, BuildService.BuildRunner runner, Probe probe,
                 Duration verifyTimeout, Duration waitStep) {
        this.store = store;
        this.builds = builds;
        this.deploys = deploys;
        this.awsCicd = cicd;
        this.awsProvisioner = provisioner;
        this.clouds = clouds;
        this.addresses = addresses;
        this.copy = copy;
        this.certificates = certificates;
        this.runner = runner;
        this.probe = probe;
        this.verifyTimeout = verifyTimeout;
        this.waitStep = waitStep;
    }

    /**
     * 옮기기를 시작한다. 확인은 바로 하고, 단계는 뒤에서 돈다. 진행은 {@link #status} 로 본다.
     *
     * @param request 이 앱의 평소 배포 요청(환경변수 포함)에 {@code cloudProvider} 만 옮길 클라우드로 바꾼 것.
     *                builder 는 환경변수를 저장하지 않아서 화면이 보낸다
     * @throws IllegalArgumentException 요청이 맞지 않다
     * @throws IllegalStateException    지금은 옮길 수 없다 (이유를 담는다)
     */
    public Build start(String app, BuildRequest request) {
        if (!app.equals(request.appName())) {
            throw new IllegalArgumentException("appName 이 경로와 다르다");
        }
        if (request.isStandby() || request.onPremOnly()) {
            throw new IllegalArgumentException("클라우드 배포 요청이 아니다");
        }
        String to = request.cloudProviderOrDefault();
        String from = deploys.cloudProvider(app);
        if (to.equals(from)) {
            throw new IllegalArgumentException("이미 " + from + " 에 있다");
        }
        if (!moving.add(app)) {
            throw new IllegalStateException(app + " 은 이미 옮기는 중이다");
        }
        try {
            Plan plan = check(app, from, to);
            Build record = new Build(UUID.randomUUID().toString().substring(0, 8), request);
            record.log(RECORD + "from=" + from + " to=" + to);
            record.log("deploymentMode=HYBRID");
            record.log("cloudProvider=" + from);
            record.log("migrate: database " + (plan.database() == null ? "none" : plan.database()));
            record.status(Build.Status.DEPLOYING, "migrate: started");
            store.save(record);
            runner.run(() -> {
                try {
                    run(record, request, plan);
                } finally {
                    moving.remove(app);
                }
            });
            return record;
        } catch (RuntimeException e) {
            moving.remove(app);
            throw e;
        }
    }

    /** 옮기기 전 확인 결과 */
    record Plan(String app, String from, String to, String database) {
    }

    Plan check(String app, String from, String to) {
        if (!addresses.enabled()) {
            throw new IllegalStateException("공개 주소(Cloudflare 존)가 설정되지 않아 주소를 옮길 수 없다");
        }
        if ("GCP".equals(from) || "GCP".equals(to)) {
            CloudProfiles gcp = clouds == null ? null : clouds.profile();
            if (gcp == null || !gcp.deployConfigured() || !gcp.provisionerConfigured() || gcp.origin().isBlank()) {
                throw new IllegalStateException("GCP 가 연결되지 않았다 (GCP_CICD_URL, GCP_REGISTRY, GCP_PROVISIONER_URL, GCP_CUTOVER_ORIGIN)");
            }
        }
        List<Build> history = store.findAll().stream().filter(b -> app.equals(b.getAppName())).toList();
        if (history.isEmpty()) {
            throw new IllegalStateException(app + " 의 배포 기록이 없다");
        }
        if (history.stream().anyMatch(b -> AgentDeployService.onPrem(b)
                || b.getLogs().stream().anyMatch(l -> l.startsWith(AgentBurst.STANDBY_MARK)))) {
            throw new IllegalStateException("내 PC(하이브리드) 앱은 아직 옮기지 않는다. 거점을 내 PC 로 옮긴 뒤 다시 배포한다");
        }
        if (history.stream().anyMatch(b -> RUNNING.contains(b.getStatus()))) {
            throw new IllegalStateException(app + " 은 배포가 진행 중이다");
        }
        AppAddress.State address = addresses.state(app);
        if (address.home() != AppAddress.Home.CLOUD) {
            throw new IllegalStateException("공개 주소가 클라우드를 가리키지 않는다: " + address.home());
        }
        JsonNode source = json(cicd(from).release(app), "원본 " + from + " 릴리스");
        // canary 전략은 activeSlot 이 비어 있고 slots(stable·canary)만 있다
        if (source.path("slots").size() == 0) {
            throw new IllegalStateException(from + " 에 떠 있는 " + app + " 이 없다");
        }
        CicdClient.Passthrough targetRelease = cicd(to).release(app);
        if (targetRelease.status() == 200 && json(targetRelease, to + " 릴리스").path("slots").size() > 0) {
            throw new IllegalStateException(to + " 에 이미 " + app + " 이 있다");
        }
        String database = null;
        for (JsonNode slot : source.path("slots")) {
            String engine = slot.path("database").asText("");
            if (!engine.isBlank() && !"null".equals(engine)) {
                database = engine;
            }
        }
        if (database != null) {
            if (!"postgres".equals(database)) {
                throw new IllegalStateException(database + " DB 는 아직 옮기지 않는다 (PostgreSQL 만)");
            }
            CicdClient.Passthrough schema = cicd(from).schema(app);
            if (schema.status() == 200 && "pgroll".equals(json(schema, "스키마").path("engine").asText(""))) {
                throw new IllegalStateException("pgroll 로 관리하는 DB 는 아직 옮기지 않는다 (pgroll 상태 스키마와 이벤트 트리거를 옮기지 않는다)");
            }
            if (provisioner(to).engine(app).isPresent()) {
                throw new IllegalStateException(to + " 에 이미 " + app + " DB 가 있다");
            }
            if (provisioner(from).engine(app).isEmpty()) {
                throw new IllegalStateException(from + " 에 " + app + " DB 가 없다");
            }
            if (("GCP".equals(from) || "GCP".equals(to))
                    && (!clouds.profile().tunnelConfigured() || !certificates.enabled())) {
                throw new IllegalStateException("Cloud SQL 로 가는 터널이 없다 (GCP_TUNNEL_SSH_HOST, GCP_TUNNEL_REMOTE_HOST, 터널 CA)");
            }
        }
        return new Plan(app, from, to, database);
    }

    void run(Build record, BuildRequest request, Plan plan) {
        String app = plan.app();
        Step step = null;
        Instant frozen = null;
        try {
            step = step(record, Step.PREPARE);
            Build image = await(builds.prepareImage(request, PART + record.getId()), "이미지");
            record.log("migrate: image " + image.getImage() + " commit " + image.getCommit());

            String sourceUrl = null;
            String targetUrl = null;
            if (plan.database() != null) {
                step = step(record, Step.DATABASE);
                ProvisionerClient target = provisioner(plan.to());
                target.ensure(app, plan.database(), "127.0.0.1", DatabaseCopy.LOCAL_PORT);
                sourceUrl = databaseUrl(provisioner(plan.from()), app, "GCP".equals(plan.from()));
                targetUrl = databaseUrl(target, app, "GCP".equals(plan.to()));
            }

            step = step(record, Step.FREEZE);
            frozen = Instant.now();
            CicdClient.Passthrough stopped = cicd(plan.from()).stop(app);
            if (stopped.status() != 200) {
                throw new IllegalStateException(plan.from() + " 앱을 내리지 못했다: " + stopped.status() + " " + stopped.body());
            }

            if (plan.database() != null) {
                step = step(record, Step.COPY);
                String result = copy.copy(record.getId(), sourceUrl, targetUrl, tunnel(record.getId()));
                result.lines().filter(line -> line.startsWith("copy:")).forEach(line -> record.log("migrate: " + line));
                store.save(record);
            }

            step = step(record, Step.DEPLOY);
            await(builds.deployImage(request, image.getImage(), image.getCommit(), PART + record.getId()), "배포");

            step = step(record, Step.SWITCH);
            addresses.pointCloud(app, origin(plan.to()));
            verify(app);
            long downtime = Duration.between(frozen, Instant.now()).toMillis();
            record.log("migrate: downtime " + downtime + "ms");
            record.log(COMMITTED);
            record.log("cloudProvider=" + plan.to());
            record.url("https://" + addresses.host(app));
            record.status(Build.Status.SUCCEEDED, "migrate: hold (" + plan.from() + " 앱은 replicas 0, DB 보관)");
            store.save(record);
            log.info("migration committed: app={} {} -> {} downtime={}ms", app, plan.from(), plan.to(), downtime);
        } catch (RuntimeException e) {
            log.warn("migration failed: app={} step={} message={}", app, step, e.getMessage());
            record.log("migrate: failed at " + step + ": " + e.getMessage());
            undo(record, plan, step);
            record.status(Build.Status.FAILED, "failed: " + e.getMessage());
            store.save(record);
        }
    }

    /** 실패한 단계까지 한 일을 되돌린다. 하나가 실패해도 나머지는 한다 */
    private void undo(Build record, Plan plan, Step failed) {
        if (failed == null || failed == Step.PREPARE) {
            return;
        }
        String app = plan.app();
        if (failed.compareTo(Step.FREEZE) >= 0) {
            quietly(record, "원본 다시 올리기", () -> {
                CicdClient.Passthrough started = cicd(plan.from()).start(app);
                if (started.status() != 200) {
                    throw new IllegalStateException(started.status() + " " + started.body());
                }
            });
        }
        if (failed == Step.SWITCH) {
            quietly(record, "주소 되돌리기", () -> addresses.pointCloud(app, origin(plan.from())));
        }
        if (failed.compareTo(Step.DEPLOY) >= 0) {
            quietly(record, plan.to() + " 앱 지우기", () -> cicd(plan.to()).remove(app, plan.database() != null));
        }
        if (plan.database() != null) {
            quietly(record, plan.to() + " DB 지우기", () -> provisioner(plan.to()).delete(app));
        }
    }

    /**
     * HOLD 를 되돌린다: 원본을 다시 올리고 주소를 원본으로 바꾼 뒤 새 쪽 앱과 DB 를 지운다.
     * 옮긴 뒤 새 쪽에 쓴 데이터는 버린다 (원본 DB 는 옮길 때의 상태다).
     */
    public Build rollback(String app) {
        Build record = holding(app);
        Plan plan = planOf(record);
        if (!moving.add(app)) {
            throw new IllegalStateException(app + " 은 이미 옮기는 중이다");
        }
        try {
            CicdClient.Passthrough started = cicd(plan.from()).start(app);
            if (started.status() != 200) {
                throw new IllegalStateException(plan.from() + " 앱을 다시 올리지 못했다: " + started.status() + " " + started.body());
            }
            awaitReady(cicd(plan.from()), app);
            addresses.pointCloud(app, origin(plan.from()));
            quietly(record, plan.to() + " 앱 지우기", () -> cicd(plan.to()).remove(app, plan.database() != null));
            record.log(ROLLED_BACK);
            record.log("cloudProvider=" + plan.from());
            record.status(Build.Status.ROLLED_BACK, "migrate: " + plan.from() + " 로 되돌렸다 (" + plan.to() + " 에 쓴 데이터는 버렸다)");
            store.save(record);
            return record;
        } finally {
            moving.remove(app);
        }
    }

    /** HOLD 를 끝낸다: 원본 클러스터의 앱과 DB 를 지운다. 공개 주소는 건드리지 않는다 */
    public Build finalizeMigration(String app) {
        Build record = holding(app);
        Plan plan = planOf(record);
        CicdClient.Passthrough removed = cicd(plan.from()).remove(app, plan.database() != null);
        if (removed.status() != 200 && removed.status() != 404) {
            throw new IllegalStateException(plan.from() + " 앱을 지우지 못했다: " + removed.status() + " " + removed.body());
        }
        record.log(FINALIZED);
        record.log("migrate: " + plan.from() + " 정리 " + removed.status());
        store.save(record);
        return record;
    }

    /** 앱을 지울 때. HOLD 중이면 원본 쪽 잔여물(replicas 0 앱, DB)도 지운다 */
    public void onRemoved(String app) {
        latest(app).filter(this::isHolding).ifPresent(record -> {
            try {
                finalizeMigration(app);
            } catch (RuntimeException e) {
                log.warn("migration leftovers not removed: app={} message={}", app, e.getMessage());
            }
        });
    }

    /** 이 앱의 가장 최근 옮기기 기록 */
    public Optional<Build> latest(String app) {
        return store.findAll().stream()
                .filter(b -> app.equals(b.getAppName()))
                .filter(AppMigration::isRecord)
                .max(Comparator.comparing(Build::getCreatedAt));
    }

    /** 화면에 보일 상태 */
    public Optional<View> status(String app) {
        return latest(app).map(record -> {
            Plan plan = planOf(record);
            String state = switch (record.getStatus()) {
                case DEPLOYING, QUEUED, BUILDING -> "RUNNING";
                case SUCCEEDED -> record.getLogs().contains(FINALIZED) ? "FINALIZED" : "HOLD";
                case ROLLED_BACK -> "ROLLED_BACK";
                default -> "FAILED";
            };
            String step = record.getLogs().stream().filter(l -> l.startsWith(STEP))
                    .reduce((a, b) -> b).map(l -> l.substring(STEP.length())).orElse("");
            Long downtime = record.getLogs().stream().filter(l -> l.startsWith("migrate: downtime "))
                    .map(l -> Long.parseLong(l.replaceAll("\\D+", ""))).findFirst().orElse(null);
            return new View(app, record.getId(), plan.from(), plan.to(), state, step, downtime,
                    record.getCreatedAt(), record.getUpdatedAt(), record.getLogs());
        });
    }

    public record View(String appName, String id, String from, String to, String state, String step,
                       Long downtimeMs, Instant startedAt, Instant updatedAt, List<String> logs) {
    }

    /** 옮기는 중이다. 배포·롤백·내리기를 막는다 */
    public boolean inProgress(String app) {
        return moving.contains(app) || latest(app).map(r -> RUNNING.contains(r.getStatus())).orElse(false);
    }

    /**
     * 옮긴 앱에 옛 클라우드로 배포가 오면 막는다. 화면의 프로젝트 클라우드가 아직 바뀌지 않았을 때
     * 옛 클라우드에 옛 DB 로 같은 앱이 다시 뜨는 것(데이터가 둘로 갈라짐)을 막는다.
     *
     * @return 막을 이유. 막지 않으면 empty
     */
    public Optional<String> conflict(BuildRequest request) {
        String app = request.appName();
        if (app == null || request.isStandby()) {
            return Optional.empty();
        }
        if (inProgress(app)) {
            return Optional.of(app + " 은 다른 클라우드로 옮기는 중이다");
        }
        Optional<Build> newest = store.findAll().stream()
                .filter(b -> app.equals(b.getAppName()))
                .filter(b -> b.getLogs().stream().noneMatch(l -> l.startsWith(PART)))
                .max(Comparator.comparing(Build::getCreatedAt));
        if (newest.isEmpty() || !isRecord(newest.get())) {
            return Optional.empty();
        }
        String current = AgentDeployService.lastCloudProvider(newest.get());
        String wanted = request.cloudProviderOrDefault();
        return current.equals(wanted) ? Optional.empty()
                : Optional.of(app + " 은 " + current + " 에 있다. 프로젝트 클라우드를 " + current + " 로 맞춘 뒤 배포한다");
    }

    /**
     * builder 가 옮기는 도중에 다시 뜨면 이어서 할 수 없다. 전환을 마치지 않은 기록은 원본으로 되돌리고 FAILED 로 닫는다.
     */
    public void resumeInterrupted() {
        for (Build record : store.findAll()) {
            if (!isRecord(record) || !RUNNING.contains(record.getStatus())) {
                continue;
            }
            Plan plan = planOf(record);
            if (record.getLogs().contains(COMMITTED)) {
                record.status(Build.Status.SUCCEEDED, "migrate: hold");
                store.save(record);
                continue;
            }
            Step step = record.getLogs().stream().filter(l -> l.startsWith(STEP)).reduce((a, b) -> b)
                    .map(l -> Step.valueOf(l.substring(STEP.length()))).orElse(null);
            log.warn("migration interrupted: app={} step={}", plan.app(), step);
            record.log("migrate: builder restarted at " + step + ", undoing");
            undo(record, plan, step);
            record.status(Build.Status.FAILED, "failed: builder 가 옮기는 도중에 다시 시작했다");
            store.save(record);
        }
    }

    private Build holding(String app) {
        Build record = latest(app).orElseThrow(() -> new IllegalStateException(app + " 은 옮긴 기록이 없다"));
        if (!isHolding(record)) {
            throw new IllegalStateException(app + " 은 되돌리거나 정리할 수 있는 상태가 아니다");
        }
        return record;
    }

    private boolean isHolding(Build record) {
        return record.getStatus() == Build.Status.SUCCEEDED && !record.getLogs().contains(FINALIZED);
    }

    static boolean isRecord(Build build) {
        return build.getLogs().stream().anyMatch(l -> l.startsWith(RECORD));
    }

    static Plan planOf(Build record) {
        String line = record.getLogs().stream().filter(l -> l.startsWith(RECORD)).findFirst().orElseThrow();
        String from = line.replaceAll(".*from=(\\w+).*", "$1");
        String to = line.replaceAll(".*to=(\\w+).*", "$1");
        String database = record.getLogs().stream().filter(l -> l.startsWith("migrate: database "))
                .findFirst().map(l -> l.substring("migrate: database ".length())).orElse("none");
        return new Plan(record.getAppName(), from, to, "none".equals(database) ? null : database);
    }

    private Step step(Build record, Step step) {
        record.log(STEP + step);
        store.save(record);
        return step;
    }

    private void quietly(Build record, String what, Runnable action) {
        try {
            action.run();
            record.log("migrate: undo " + what);
        } catch (RuntimeException e) {
            record.log("migrate: undo " + what + " failed: " + e.getMessage());
            log.warn("migration undo failed: app={} {} {}", record.getAppName(), what, e.getMessage());
        }
    }

    /** 옮기는 중에 만든 빌드가 끝나기를 기다린다 */
    private Build await(Build started, String what) {
        long deadline = System.nanoTime() + Duration.ofMinutes(30).toNanos();
        while (System.nanoTime() < deadline) {
            Build current = store.find(started.getId()).orElse(started);
            switch (current.getStatus()) {
                case SUCCEEDED -> {
                    return current;
                }
                case FAILED, ROLLED_BACK, CANCELLED -> {
                    String last = current.getLogs().isEmpty() ? "" : current.getLogs().get(current.getLogs().size() - 1);
                    throw new IllegalStateException(what + " 빌드 " + current.getId() + " 실패: " + last);
                }
                default -> sleep(waitStep);
            }
        }
        throw new IllegalStateException(what + " 빌드가 30분 안에 끝나지 않았다");
    }

    private void awaitReady(CicdClient client, String app) {
        long deadline = System.nanoTime() + verifyTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            CicdClient.AppStatus status = client.status(app);
            if (status != null && status.readyReplicas() >= 1) {
                return;
            }
            sleep(waitStep);
        }
        throw new IllegalStateException(app + " 이 " + verifyTimeout.toSeconds() + "초 안에 Ready 가 되지 않았다");
    }

    /** 공개 주소가 새 클라우드에서 5xx 가 아닌 응답을 줄 때까지 (원본은 내려가 있어 5xx 다) */
    private void verify(String app) {
        String url = "https://" + addresses.host(app) + "/";
        long deadline = System.nanoTime() + verifyTimeout.toNanos();
        int last = -1;
        while (System.nanoTime() < deadline) {
            last = probe.status(url);
            if (last >= 200 && last < 500) {
                return;
            }
            sleep(waitStep);
        }
        throw new IllegalStateException("공개 주소가 응답하지 않는다: " + url + " (마지막 " + last + ")");
    }

    private String databaseUrl(ProvisionerClient provisioner, String app, boolean tunneled) {
        Map<String, String> env = provisioner.existing(app, tunneled ? "127.0.0.1" : null,
                        tunneled ? DatabaseCopy.LOCAL_PORT : null)
                .orElseThrow(() -> new IllegalStateException(app + " DB 를 찾지 못했다"))
                .env();
        String url = env.get("DATABASE_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(app + " DB 접속 정보에 DATABASE_URL 이 없다");
        }
        return url;
    }

    /** Cloud SQL 은 사설 IP 라 GCP 배스천을 거친다. RDS 는 builder 클러스터와 같은 VPC 라 터널이 없다 */
    private DatabaseCopy.Tunnel tunnel(String id) {
        CloudProfiles gcp = clouds.profile();
        return new DatabaseCopy.Tunnel(gcp.tunnelSshHost(), gcp.tunnelSshUser(),
                gcp.tunnelRemoteHost() + ":" + gcp.tunnelRemotePort(), certificates.jobKey("dbcopy-" + id, 30));
    }

    private CicdClient cicd(String provider) {
        return "GCP".equals(provider) ? clouds.cicd() : awsCicd;
    }

    private ProvisionerClient provisioner(String provider) {
        return "GCP".equals(provider) ? clouds.provisioner() : awsProvisioner;
    }

    /** AWS 면 null ({@link AppAddress} 의 기본값 ALB) */
    private String origin(String provider) {
        return "GCP".equals(provider) ? clouds.origin() : null;
    }

    private static JsonNode json(CicdClient.Passthrough response, String what) {
        if (response.status() != 200) {
            throw new IllegalStateException(what + " 을 읽지 못했다: " + response.status() + " " + response.body());
        }
        try {
            return JSON.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException(what + " 응답이 JSON 이 아니다");
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private static int httpStatus(String url) {
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            return -1;
        }
    }
}
