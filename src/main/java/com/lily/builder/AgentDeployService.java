package com.lily.builder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 온프레미스 배포. 빌드·실행은 사용자 PC 의 에이전트가 하고, 여기서는 잡을 보내고 에이전트가 보내는 단계를 Build 로 기록한다.
 * 클라우드 배포와 같은 Build 로 남기므로 lily-frontend 는 GET /api/builds/{id} 로 똑같이 상태를 본다.
 *
 * <pre>
 * 에이전트 상태      Build 상태
 * QUEUED·CHECKOUT·ANALYZE      QUEUED (레포 확인)
 * BUILDING                     BUILDING
 * STARTING·HEALTH·JUDGING·SWITCHING    DEPLOYING
 * SUCCEEDED / FAILED           SUCCEEDED (url) / FAILED
 * </pre>
 *
 * 실패 이유는 마지막 로그 줄에 남는다 (lily-frontend 가 목록에 보여 준다).
 */
@Service
public class AgentDeployService {

    /** 에이전트(lily-on-premise DeployJob)의 앱 이름 규칙. 클라우드보다 짧다 */
    static final Pattern APP_NAME = Pattern.compile("[a-z][a-z0-9-]{0,30}");
    private static final String DEFAULT_HEALTH_PATH = "/actuator/health/readiness";
    /** 잡을 보낸 직후 남기는 줄. 뒤에 에이전트 프로세스 id 가 붙는다 */
    private static final String SENT_TO = "agent: send to ";
    private static final Logger log = LoggerFactory.getLogger(AgentDeployService.class);

    private final BuildStore store;
    private final GitHubSource github;
    private final BuildService builds;
    private final AgentHub hub;
    private final BuildService.BuildRunner runner;
    private final BuilderProperties props;
    private final ProvisionerClient provisioner;
    private final CloudClients clouds;
    private final CloudWelcome welcome;
    private final ObjectMapper json = new ObjectMapper();
    /** 진행 중인 온프레미스 빌드 id → 에이전트 key. 다른 에이전트가 보낸 상태는 받지 않는다 */
    private final Map<String, String> running = new ConcurrentHashMap<>();
    /** 롤백·거점 전환 id → 에이전트가 SUCCEEDED/FAILED 를 보낼 때까지 기다리는 응답 */
    private final Map<String, CompletableFuture<String>> rollbackWaiters = new ConcurrentHashMap<>();
    /**
     * 취소, 에이전트가 보낸 단계, 잡 보내기 직전 저장, 제한 시간 정리를 한 번에 하나씩 한다.
     * 늦게 온 단계나 보내기 전 저장이 CANCELLED 를 덮지 않게 한다
     */
    private final Object transitions = new Object();

    public AgentDeployService(BuildStore store, GitHubSource github, BuildService builds, AgentHub hub,
                              BuildService.BuildRunner runner, BuilderProperties props,
                              ProvisionerClient provisioner) {
        this(store, github, builds, hub, runner, props, provisioner, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentDeployService(BuildStore store, GitHubSource github, BuildService builds, AgentHub hub,
                              BuildService.BuildRunner runner, BuilderProperties props,
                              ProvisionerClient provisioner, CloudClients clouds, CloudWelcome welcome) {
        this.store = store;
        this.provisioner = provisioner;
        this.clouds = clouds;
        this.welcome = welcome;
        this.github = github;
        this.builds = builds;
        this.hub = hub;
        this.runner = runner;
        this.props = props;
    }

    /** @throws IllegalArgumentException 에이전트 규칙에 맞지 않는 앱 이름 */
    public Build start(String agentKey, BuildRequest request) {
        if ("MULTI".equals(request.cloudProvider())) {
            throw new IllegalArgumentException("내 PC 앱은 멀티클라우드(AWS + GCP)로 배포하지 않는다. AWS 나 GCP 하나를 고른다");
        }
        if (!APP_NAME.matcher(request.appName()).matches()) {
            throw new IllegalArgumentException("온프레미스 앱 이름은 영문 소문자로 시작하고 31자 이하여야 한다: " + request.appName());
        }
        Build build = new Build(UUID.randomUUID().toString().substring(0, 8), request);
        build.log("queued: " + request.repoUrl() + " branch=" + build.getBranch() + " target=onprem agent=" + agentKey);
        build.log("deploymentMode=" + request.deploymentModeOrDefault());
        build.log("cloudProvider=" + request.cloudProviderOrDefault());
        // 배포 화면의 엣지 체크박스. 배포가 끝나면 warmEdge 가 읽어 앱 DO 설정에 넣는다 (비우면 앱의 지금 설정 그대로)
        if (request.edgeSnapshot() != null) {
            build.log(EDGE_SNAPSHOT + (request.edgeSnapshot() ? "on" : "off"));
        }
        if (request.edgeQueue() != null) {
            build.log(EDGE_QUEUE + (request.edgeQueue() ? "on" : "off"));
        }
        store.save(build);
        running.put(build.getId(), agentKey);
        runner.run(() -> send(build, request, agentKey));
        return build;
    }

    void send(Build build, BuildRequest request, String agentKey) {
        try {
            if (!hub.connected(agentKey)) {
                throw new IllegalStateException("에이전트가 연결돼 있지 않다. 내 PC 에서 에이전트를 실행했는지 확인한다");
            }
            String commit = github.resolveCommit(request);
            build.commit(commit);
            build.log("source: commit " + commit);
            // 클라우드와 같은 Dockerfile 을 쓴다. 레포에 있으면 null 이라 에이전트가 레포 것을 쓴다
            BuildService.Source source = builds.source(build, request, commit);
            String dockerfile = source.dockerfile();
            BuildRequest resolved = builds.detect(build, source.request(), commit, dockerfile, source.detectDir());

            String database = resolved.database();
            boolean only = request.onPremOnly();
            String provider = request.cloudProviderOrDefault();
            if ("GCP".equals(provider)) {
                if (!hub.supports(agentKey, "cloud-target")) {
                    throw new IllegalStateException("내 PC 에이전트가 GCP 클라우드를 받지 못하는 판이다."
                            + " 에이전트를 최신 이미지로 다시 실행한다");
                }
                if (welcome == null) {
                    throw new IllegalStateException(CloudClients.MISSING);
                }
            }
            if (only && request.importsDatabase()) {
                throw new IllegalArgumentException("온프레미스 전용은 RDS 데이터를 옮기지 않는다");
            }
            String mode = only ? "local" : request.databaseModeOrDefault();
            boolean onPremDatabase = database != null && !database.isBlank() && (!"cloud".equals(mode) || only);
            if (onPremDatabase) {
                // DB 를 내 PC(local) 나 사용자가 준 주소(external) 에 둔다. RDS 를 만들지 않는다
                if (!hub.supportsDatabaseMode(agentKey, mode)) {
                    throw new IllegalStateException("내 PC 에이전트가 이 DB 위치(" + mode + ")를 지원하지 않는다."
                            + " 에이전트를 최신 이미지로 다시 실행한다");
                }
                if ("external".equals(mode) && (request.databaseUrl() == null || request.databaseUrl().isBlank())) {
                    throw new IllegalArgumentException("DB 주소(databaseUrl)가 없다");
                }
                if (request.importsDatabase()) {
                    // 클라우드 앱을 내 PC 로 옮긴다. 같은 appName 의 RDS 를 터널로 읽어 PC DB 에 채운다
                    if (!"local".equals(mode) || !"postgres".equals(database)) {
                        throw new IllegalArgumentException("RDS 데이터 옮기기는 DB 위치 local, postgres 만 된다");
                    }
                    if (!hub.supportsDatabaseMode(agentKey, "import") || !hub.platformDatabase(agentKey)) {
                        throw new IllegalStateException("내 PC 에이전트가 RDS 데이터를 옮기지 못하는 판이다."
                                + " 에이전트를 최신 이미지로 다시 실행한다");
                    }
                }
                build.log("database: " + database + " on " + ("local".equals(mode) ? "agent (my pc)" : "external url"));
            } else if (database != null && !database.isBlank() && !hub.supportsDatabase(agentKey)) {
                // DB 없이 보내면 앱이 DB 에 붙으려다 기동하지 못하고, 헬스 체크 제한 시간(3분) 뒤에야 실패한다.
                // (lily-blog-sample 은 이미지가 prod 프로파일로 고정이라 내장 DB 로 뜨지 않는다) 보내기 전에 이유와 함께 끝낸다
                throw new IllegalStateException("이 앱은 DB(" + database + ")가 필요한데 내 PC 에이전트에 DB 터널이 없다."
                        + " 에이전트를 최신 이미지로 다시 실행한다");
            }
            // 플랫폼 DB 터널이면 에이전트는 DB 계정을 따로 받지 않는다. 터널 주소 기준 접속 정보를 잡에 싣는다
            Map<String, String> databaseEnv = null;
            boolean importing = onPremDatabase && request.importsDatabase() && !only;
            boolean cloudDb = !only && (importing || !onPremDatabase) && database != null && !database.isBlank()
                    && hub.platformDatabase(agentKey);
            if ("GCP".equals(provider)) {
                welcome.retarget(agentKey, resolved.appName(), cloudDb);
            } else if (!only && welcome != null && hub.supports(agentKey, "cloud-target")) {
                // 이 에이전트가 전에 GCP 를 봤으면 AWS 로 되돌린다 (같은 값이면 에이전트는 바꾸지 않는다)
                try {
                    if (welcome.retargetAws(agentKey, resolved.appName())) {
                        build.log("cloud: agent targets AWS");
                    }
                } catch (RuntimeException e) {
                    log.warn("aws retarget failed: key={} app={} message={}", agentKey, resolved.appName(), e.getMessage());
                }
            }
            if (cloudDb) {
                AgentHub.Tunnel at = hub.tunnel(agentKey);
                databaseEnv = provisionerOf(provider).ensure(resolved.appName(), database, at.host(), at.port()).env();
                build.log("database: " + database + " via platform tunnel " + at.host() + ":" + at.port()
                        + (importing ? " (import into my pc)" : ""));
            }
            // 보낸 뒤에 기록하면 에이전트가 먼저 보낸 BUILDING 을 덮을 수 있다. 보내기 전에 남긴다
            synchronized (transitions) {
                if (cancelled(build)) {
                    return;
                }
                build.log(SENT_TO + hub.agentId(agentKey));
                store.save(build);
            }
            // PostgreSQL 은 db/pgroll 이 있으면 pgroll 파일을 보낸다 (에이전트가 무중단으로 적용한다). MySQL 은 Flyway SQL 만
            Map<String, String> migrations = "postgres".equals(database)
                    ? github.migrations(resolved, commit) : github.sqlMigrations(resolved, commit);
            if (pgroll(migrations)) {
                if (!hub.supports(agentKey, "pgroll")) {
                    throw new IllegalStateException("내 PC 에이전트가 pgroll 마이그레이션을 받지 못하는 판이다."
                            + " 에이전트를 최신 이미지로 다시 실행한다");
                }
                if (databaseEnv != null) {
                    // RDS 에 pgroll 을 켠다. init 은 이벤트 트리거라 관리자 권한이 있는 provisioner 가 한다
                    provisionerOf(provider).enablePgroll(resolved.appName());
                    build.log("database: pgroll enabled on RDS");
                }
                build.log("source: pgroll migrations " + migrations.keySet());
            }
            Map<String, Object> job = job(build.getId(), resolved, database, dockerfile, migrations, resolved.canaryPath());
            if (databaseEnv != null) {
                job.put("databaseEnv", databaseEnv);
            }
            job.put("deploymentMode", request.deploymentModeOrDefault());
            if (onPremDatabase) {
                job.put("databaseMode", mode);
                if ("external".equals(mode)) {
                    job.put("databaseUrl", request.databaseUrl());
                }
                if (importing) {
                    job.put("importDatabase", true);
                }
            }
            String message = json.writeValueAsString(job);
            synchronized (transitions) {
                if (cancelled(build)) {
                    return;
                }
                hub.send(agentKey, message);
            }
        } catch (RuntimeException | JsonProcessingException e) {
            synchronized (transitions) {
                if (!cancelled(build)) {
                    fail(build, e.getMessage());
                }
            }
        }
    }

    /** 잡을 보내기 전에 취소됐다 ({@link #cancel} 이 running 에서 뺐다) */
    private boolean cancelled(Build build) {
        if (running.containsKey(build.getId())) {
            return false;
        }
        log.info("onprem build cancelled before send: id={}", build.getId());
        return true;
    }

    /** 에이전트로 보낸 온프레미스 빌드다. 클라우드 대기 배포(버스팅)는 아니다 */
    public static boolean onPrem(Build build) {
        return build.getLogs().stream().anyMatch(line -> line.contains("target=onprem agent="));
    }

    /**
     * 온프레미스 빌드를 멈춘다. 에이전트에 취소를 보내고 바로 CANCELLED 로 닫는다. 에이전트는 트래픽을 새 버전으로
     * 바꾸기 전이면 후보를 지우고 멈춘다. 그 뒤에 에이전트가 보내는 이 빌드의 단계는 받지 않는다.
     * 에이전트가 끊겼으면 (잡이 이미 사라졌다) 기록만 닫는다.
     *
     * @throws IllegalStateException 이미 끝난 빌드
     */
    public Build cancel(String id) {
        synchronized (transitions) {
            Build build = store.find(id).orElseThrow(() -> new java.util.NoSuchElementException(id));
            if (!inFlight(build)) {
                throw new IllegalStateException("이미 끝난 배포다: " + build.getStatus());
            }
            running.remove(id);
            String key = agentKey(build);
            if (key == null || !hub.connected(key)) {
                build.log("cancel: 에이전트가 연결돼 있지 않아 기록만 닫는다");
            } else if (!hub.supports(key, "cancel")) {
                build.log("cancel: 에이전트가 취소를 받지 못하는 판이라 PC 작업이 끝까지 갈 수 있다. 최신 이미지로 다시 실행한다");
            } else {
                try {
                    hub.send(key, json.writeValueAsString(Map.of("type", "cancel", "id", id)));
                    build.log("cancel: 에이전트에 보냈다");
                } catch (JsonProcessingException | RuntimeException e) {
                    build.log("cancel: 에이전트에 보내지 못했다 " + e.getMessage());
                }
            }
            update(build, Build.Status.CANCELLED, "cancelled: 사용자가 취소했다");
            log.info("onprem build cancelled: id={} app={} agent={}", id, build.getAppName(), key);
            return build;
        }
    }

    /**
     * 이 앱의 최근 성공이 온프레미스 배포면 그 에이전트의 직전 슬롯으로 되돌린다.
     * @return 롤백 결과 JSON. 최근 성공이 클라우드면 empty (호출자가 lily-cicd 로 넘긴다)
     */
    private static boolean pgroll(Map<String, String> migrations) {
        return migrations != null && migrations.keySet().stream()
                .anyMatch(name -> name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json"));
    }

    /**
     * 온프레미스 앱의 스키마 이력. 에이전트가 3초마다 보내는 상태(burst-state)의 schema 를 돌려준다.
     * lily-cicd GET /api/deployments/{app}/schema 와 같은 모양이다. 온프레미스 앱이 아니면 빈 값
     */
    public Optional<String> schema(String app) {
        String key = agentKeyOf(app);
        if (key == null) {
            return Optional.empty();
        }
        JsonNode state = hub.lastState(key);
        try {
            if (state != null && app.equals(state.path("app").asText("")) && state.has("schema")) {
                return Optional.of(json.writeValueAsString(state.get("schema")));
            }
            Map<String, Object> empty = new java.util.LinkedHashMap<>();
            empty.put("appName", app);
            empty.put("engine", null);
            empty.put("currentVersion", null);
            empty.put("window", null);
            empty.put("slots", java.util.List.of());
            empty.put("history", java.util.List.of());
            empty.put("message", "내 PC 에이전트가 아직 스키마 상태를 보내지 않았다");
            return Optional.of(json.writeValueAsString(empty));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 온프레미스 앱의 pgroll 롤백 창을 바로 닫으라고 에이전트에 보낸다. 온프레미스 앱이 아니면 빈 값 */
    public Optional<String> completeSchema(String app) {
        String key = agentKeyOf(app);
        if (key == null) {
            return Optional.empty();
        }
        if (!hub.connected(key)) {
            throw new IllegalStateException("온프레미스 에이전트가 연결돼 있지 않다");
        }
        try {
            hub.send(key, json.createObjectNode().put("type", "schema-complete").put("app", app).toString());
            return Optional.of(json.writeValueAsString(Map.of("status", "ACCEPTED", "appName", app,
                    "message", "내 PC 에이전트에 complete 를 보냈다. 상태는 몇 초 안에 바뀐다")));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 마지막으로 성공한 배포가 온프레미스면 그 에이전트 키 */
    private String agentKeyOf(String app) {
        return store.findAll().stream()
                .filter(build -> app.equals(build.getAppName()) && build.getStatus() == Build.Status.SUCCEEDED)
                .max(Comparator.comparing(Build::getCreatedAt))
                .map(AgentDeployService::agentKey)
                .orElse(null);
    }

    public Optional<String> rollback(String app) {
        Optional<Build> latest = store.findAll().stream()
                .filter(build -> app.equals(build.getAppName()) && build.getStatus() == Build.Status.SUCCEEDED)
                .max(Comparator.comparing(Build::getCreatedAt));
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        String key = agentKey(latest.get());
        if (key == null) {
            return Optional.empty();
        }
        if (!hub.connected(key)) {
            throw new IllegalStateException("온프레미스 에이전트가 연결돼 있지 않다");
        }
        String id = "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 7);
        CompletableFuture<String> done = new CompletableFuture<>();
        rollbackWaiters.put(id, done);
        try {
            hub.send(key, json.writeValueAsString(Map.of("type", "rollback", "app", app, "id", id)));
            String line = done.get(props.buildTimeoutSeconds(), TimeUnit.SECONDS);
            return Optional.of(json.writeValueAsString(Map.of(
                    "status", "ROLLED_BACK",
                    // 에이전트가 pgroll 롤백 창 안이면 스키마도 되돌린다 (마지막 줄의 schema=reverted|partial)
                    "schema", line != null && line.contains("schema=reverted") ? "reverted"
                            : line != null && line.contains("schema=partial") ? "partial" : "unchanged",
                    "message", line == null ? "" : line)));
        } catch (TimeoutException e) {
            throw new IllegalStateException("온프레미스 롤백 응답이 제한 시간 안에 오지 않았다");
        } catch (ExecutionException e) {
            String message = e.getCause() == null ? "롤백에 실패했다" : e.getCause().getMessage();
            throw new IllegalStateException(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("온프레미스 롤백이 중단되었다");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("롤백 요청을 만들지 못했다");
        } finally {
            rollbackWaiters.remove(id);
        }
    }

    /**
     * 이 앱의 최근 성공이 온프레미스 배포면 그 에이전트에 거점 전환을 보낸다.
     * @param target {@code cloud} 또는 {@code onprem}
     */
    public Optional<String> home(String app, String target) {
        return home(app, target, false);
    }

    /**
     * @param migrateDatabase 앱 DB 도 옮긴다 (클라우드로: 내 PC → RDS, 온프레미스로: RDS → 내 PC). 에이전트가 거절하면 실패다
     */
    public Optional<String> home(String app, String target, boolean migrateDatabase) {
        if (!"cloud".equals(target) && !"onprem".equals(target)) {
            throw new IllegalArgumentException("home 은 cloud 또는 onprem 이다");
        }
        Optional<String> key = agentFor(app);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        if (!hub.connected(key.get())) {
            throw new IllegalStateException("온프레미스 에이전트가 연결돼 있지 않다");
        }
        if (onPremOnly(app)) {
            throw new IllegalArgumentException("온프레미스 전용은 거점을 바꾸지 않는다");
        }
        String id = "h" + UUID.randomUUID().toString().replace("-", "").substring(0, 7);
        CompletableFuture<String> done = new CompletableFuture<>();
        rollbackWaiters.put(id, done);
        try {
            hub.send(key.get(), json.writeValueAsString(Map.of(
                    "type", "home", "app", app, "home", target, "id", id, "migrateDatabase", migrateDatabase)));
            String line = done.get(props.buildTimeoutSeconds(), TimeUnit.SECONDS);
            return Optional.of(json.writeValueAsString(Map.of(
                    "status", "MOVED",
                    "home", target,
                    "message", line == null ? "" : line)));
        } catch (TimeoutException e) {
            throw new IllegalStateException("온프레미스 거점 전환 응답이 제한 시간 안에 오지 않았다");
        } catch (ExecutionException e) {
            String message = e.getCause() == null ? "거점 전환에 실패했다" : e.getCause().getMessage();
            throw new IllegalStateException(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("온프레미스 거점 전환이 중단되었다");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("거점 전환 요청을 만들지 못했다");
        } finally {
            rollbackWaiters.remove(id);
        }
    }

    /**
     * 이 앱을 마지막으로 다룬 에이전트에 앱 삭제를 보낸다 (슬롯 컨테이너, 이미지, database 면 PC 의 앱 DB).
     *
     * @return 최근 빌드가 에이전트 빌드가 아니면 empty (클라우드 앱). 에이전트가 꺼져 있으면 status offline
     * @throws IllegalStateException 에이전트가 거절했다 (예: 거점을 옮기는 중)
     */
    public Optional<AgentRemoval> remove(String app, boolean database) {
        Optional<String> key = store.findAll().stream()
                .filter(build -> app.equals(build.getAppName()))
                .max(Comparator.comparing(Build::getCreatedAt))
                .map(AgentDeployService::agentKey);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        if (!hub.connected(key.get())) {
            return Optional.of(new AgentRemoval(key.get(), "offline", "에이전트가 꺼져 있어 PC 의 컨테이너는 남는다"));
        }
        if (!hub.supports(key.get(), "remove")) {
            return Optional.of(new AgentRemoval(key.get(), "unsupported",
                    "에이전트가 앱 삭제를 모르는 이전 버전이라 PC 의 컨테이너는 남는다"));
        }
        String id = "d" + UUID.randomUUID().toString().replace("-", "").substring(0, 7);
        CompletableFuture<String> done = new CompletableFuture<>();
        rollbackWaiters.put(id, done);
        try {
            hub.send(key.get(), json.writeValueAsString(Map.of(
                    "type", "remove", "app", app, "id", id, "database", database)));
            String line = done.get(props.buildTimeoutSeconds(), TimeUnit.SECONDS);
            return Optional.of(new AgentRemoval(key.get(), "removed", line == null ? "" : line));
        } catch (TimeoutException e) {
            throw new IllegalStateException("온프레미스 앱 삭제 응답이 제한 시간 안에 오지 않았다");
        } catch (ExecutionException e) {
            String message = e.getCause() == null ? "앱 삭제에 실패했다" : e.getCause().getMessage();
            throw new IllegalStateException(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("온프레미스 앱 삭제가 중단되었다");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("앱 삭제 요청을 만들지 못했다");
        } finally {
            rollbackWaiters.remove(id);
        }
    }

    /**
     * @param agent  에이전트 key
     * @param status removed (PC 에서 지움), offline (꺼져 있음), unsupported (이전 버전 에이전트)
     */
    public record AgentRemoval(String agent, String status, String message) {
    }

    /** 이 앱을 마지막으로 다룬 에이전트 key (가장 최근 성공 빌드 기준) */
    public Optional<String> agentOf(String app) {
        return agentFor(app);
    }

    private Optional<String> agentFor(String app) {
        Optional<Build> latest = store.findAll().stream()
                .filter(build -> app.equals(build.getAppName()) && build.getStatus() == Build.Status.SUCCEEDED)
                .max(Comparator.comparing(Build::getCreatedAt));
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        String key = agentKey(latest.get());
        return key == null ? Optional.empty() : Optional.of(key);
    }

    /** 이 에이전트로 가장 최근에 배포한 앱 (대기 배포 포함) */
    public Optional<String> latestApp(String agentKey) {
        return store.findAll().stream()
                .filter(build -> agentKey.equals(agentKey(build)))
                .max(Comparator.comparing(Build::getCreatedAt))
                .map(Build::getAppName);
    }

    /**
     * 이 앱의 가장 최근 빌드에 기록된 클라우드. 기록이 없으면 AWS.
     * 다른 클라우드로 옮기는 중에 만든 빌드({@link AppMigration#PART})는 빼고, 한 빌드에 여러 줄이면 마지막 줄을 본다
     * (옮기기 기록은 옮기기 전 클라우드를 남기고, 전환을 마치면 옮긴 클라우드를 덧붙인다)
     */
    public String cloudProvider(String app) {
        return store.findAll().stream()
                .filter(build -> app.equals(build.getAppName()))
                .filter(build -> build.getLogs().stream().noneMatch(line -> line.startsWith(AppMigration.PART)))
                .max(Comparator.comparing(Build::getCreatedAt))
                .map(AgentDeployService::lastCloudProvider)
                .orElse("AWS");
    }

    /** 이 앱의 가장 최근 빌드가 멀티클라우드(GCP + AWS)다. 옮기는 중에 만든 빌드는 뺀다 */
    public boolean multiCloud(String app) {
        return store.findAll().stream()
                .filter(build -> app.equals(build.getAppName()))
                .filter(build -> build.getLogs().stream().noneMatch(line -> line.startsWith(AppMigration.PART)))
                .max(Comparator.comparing(Build::getCreatedAt))
                .map(build -> build.getLogs().contains(BuildService.MULTI_CLOUD))
                .orElse(false);
    }

    static String lastCloudProvider(Build build) {
        String provider = "AWS";
        for (String line : build.getLogs()) {
            if (line.startsWith("cloudProvider=")) {
                provider = "cloudProvider=GCP".equals(line) ? "GCP" : "AWS";
            }
        }
        return provider;
    }

    private ProvisionerClient provisionerOf(String provider) {
        if (!"GCP".equals(provider)) {
            return provisioner;
        }
        if (clouds == null) {
            throw new IllegalStateException(CloudClients.MISSING_DB);
        }
        return clouds.provisioner();
    }

    /** 이 앱의 가장 최근 빌드에 기록된 배포 모드. 기록이 없으면 HYBRID */
    public String deploymentMode(String app) {
        return onPremOnly(app) ? "ONPREM_ONLY" : "HYBRID";
    }

    /** 최근 빌드가 온프레미스 전용이다. 대기 배포·스케일·RDS 호출 전에 본다 */
    public boolean onPremOnly(String app) {
        return store.findAll().stream()
                .filter(build -> app.equals(build.getAppName()))
                .max(Comparator.comparing(Build::getCreatedAt))
                .map(build -> build.getLogs().stream().anyMatch(line -> line.startsWith("deploymentMode=ONPREM_ONLY")))
                .orElse(false);
    }

    /** 이 앱을 가장 최근에 이 에이전트로 배포했다. 플랫폼 존의 {app}.{zone} 을 그 에이전트만 다룬다 */
    public boolean ownedBy(String agentKey, String app) {
        return store.findAll().stream()
                .filter(build -> app.equals(build.getAppName()))
                .max(Comparator.comparing(Build::getCreatedAt))
                .map(build -> agentKey.equals(agentKey(build)))
                .orElse(false);
    }

    /** 온프레미스 배포와, 그 에이전트가 요청한 클라우드 대기 배포({@link AgentBurst})에 남긴 에이전트 key */
    private static String agentKey(Build build) {
        String key = null;
        for (String line : build.getLogs()) {
            int at = line.indexOf("target=onprem agent=");
            if (at >= 0) {
                key = line.substring(at + "target=onprem agent=".length()).trim();
            } else if (line.startsWith(AgentBurst.STANDBY_MARK)) {
                key = line.substring(AgentBurst.STANDBY_MARK.length()).trim();
            }
        }
        return key == null || key.isBlank() ? null : key;
    }

    /**
     * 화면에서 정한 버스팅 설정을 이 앱의 에이전트에 보낸다.
     * @return 에이전트를 찾지 못하면 비어 있다
     * @throws IllegalStateException 에이전트가 끊겼거나 버스팅 설정을 받지 못하는 판이다
     */
    public Optional<AgentHub.Burst> burst(String app, boolean enabled, int cloudPercent) {
        if (cloudPercent < 0 || cloudPercent > 100) {
            throw new IllegalArgumentException("cloudPercent 는 0~100 이다");
        }
        Optional<String> key = agentFor(app);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        if (!hub.connected(key.get())) {
            throw new IllegalStateException("온프레미스 에이전트가 연결돼 있지 않다");
        }
        if (!hub.supports(key.get(), "burst")) {
            throw new IllegalStateException("에이전트가 버스팅 설정을 받지 못하는 판이다. 최신 이미지로 다시 실행한다");
        }
        if (enabled && onPremOnly(app)) {
            throw new IllegalArgumentException("온프레미스 전용은 버스팅을 쓰지 않는다");
        }
        try {
            hub.send(key.get(), json.writeValueAsString(Map.of(
                    "type", "burst", "app", app, "enabled", enabled, "cloudPercent", cloudPercent)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("버스팅 설정을 만들지 못했다");
        }
        return Optional.of(hub.burst(key.get(), app));
    }

    /** 이 앱의 에이전트가 마지막으로 보낸 버스팅·거점 상태. 에이전트를 찾지 못하면 비어 있다 */
    public Optional<AgentHub.Burst> burstState(String app) {
        return agentFor(app).map(key -> {
            AgentHub.Burst burst = hub.burst(key, app);
            return burst.state() == null ? burst : burst.withBuilds(builds(burst.state()));
        });
    }

    /** 에이전트가 기다리는 클라우드 빌드 (버스팅 대기 배포 standbyBuild, 거점 전환 homeBuild) 의 지금 상태 */
    private Map<String, AgentHub.Progress> builds(com.fasterxml.jackson.databind.JsonNode state) {
        Map<String, AgentHub.Progress> found = new LinkedHashMap<>();
        for (String field : new String[] {"standbyBuild", "homeBuild"}) {
            String id = state.path(field).asText("");
            if (id.isBlank()) {
                continue;
            }
            store.find(id).ifPresent(build -> {
                java.util.List<String> logs = build.getLogs();
                found.put(field, new AgentHub.Progress(build.getId(), build.getStatus().name(),
                        logs.isEmpty() ? "" : logs.get(logs.size() - 1), build.getCreatedAt(), build.getUpdatedAt()));
            });
        }
        return found;
    }

    /**
     * 진행 중인 거점 전환을 멈추라고 이 앱의 에이전트에 보낸다. 주소를 바꾸기 전이면 에이전트가 출발 거점으로 되돌린다.
     * @return 에이전트를 찾지 못하면 비어 있다
     * @throws IllegalStateException 에이전트가 끊겼거나 취소를 모르는 판이다
     */
    public Optional<AgentHub.Burst> cancelHome(String app) {
        Optional<String> key = agentFor(app);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        if (!hub.connected(key.get())) {
            throw new IllegalStateException("온프레미스 에이전트가 연결돼 있지 않다");
        }
        if (!hub.supports(key.get(), "home-cancel")) {
            throw new IllegalStateException("에이전트가 거점 전환 취소를 받지 못하는 판이다. 최신 이미지로 다시 실행한다");
        }
        try {
            hub.send(key.get(), json.writeValueAsString(Map.of("type", "home-cancel", "app", app)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("취소 요청을 만들지 못했다");
        }
        return burstState(app);
    }

    /** 에이전트가 보낸 단계. 이 에이전트로 보낸 빌드만 받는다 */
    public void agentStatus(String agentKey, String buildId, String status, String line, String url) {
        CompletableFuture<String> waiter = rollbackWaiters.get(buildId);
        if (waiter != null) {
            if ("SUCCEEDED".equals(status)) {
                waiter.complete(line == null ? "" : line);
            } else if ("FAILED".equals(status)) {
                waiter.completeExceptionally(new IllegalStateException(line == null || line.isBlank() ? "rollback failed" : line));
            }
            return;
        }
        synchronized (transitions) {
            Optional<Build> found = runningBuild(agentKey, buildId);
            if (found.isEmpty()) {
                log.debug("agent status ignored: key={} id={}", agentKey, buildId);
                return;
            }
            record(found.get(), buildId, status, line, url);
        }
    }

    private void record(Build build, String buildId, String status, String line, String url) {
        String logLine = "agent: " + status + (line == null || line.isBlank() ? "" : " " + line);
        switch (status) {
            case "SUCCEEDED" -> {
                running.remove(buildId);
                build.url(url == null || url.isBlank() ? null : url);
                update(build, Build.Status.SUCCEEDED, logLine);
                warmEdge(build);
            }
            case "FAILED" -> {
                running.remove(buildId);
                diagnose(build, logLine);
                update(build, Build.Status.FAILED, logLine);
            }
            case "BUILDING" -> update(build, Build.Status.BUILDING, logLine);
            case "STARTING", "HEALTH", "JUDGING", "SWITCHING" -> update(build, Build.Status.DEPLOYING, logLine);
            default -> update(build, build.getStatus(), logLine);
        }
    }

    /**
     * 이 에이전트로 보낸, 아직 끝나지 않은 빌드. builder 가 다시 떠서 메모리에 없으면 빌드 기록의 에이전트 key 로 확인하고
     * 다시 등록한다 (에이전트는 재연결 뒤에도 하던 잡의 단계를 계속 보낸다)
     */
    private Optional<Build> runningBuild(String agentKey, String buildId) {
        String known = running.get(buildId);
        if (known != null && !known.equals(agentKey)) {
            return Optional.empty();
        }
        Optional<Build> found = store.find(buildId);
        if (found.isEmpty()) {
            running.remove(buildId);
            return Optional.empty();
        }
        if (known == null) {
            Build build = found.get();
            if (!inFlight(build) || !agentKey.equals(agentKey(build))) {
                return Optional.empty();
            }
            running.put(buildId, agentKey);
            log.info("agent status resumed after restart: key={} id={}", agentKey, buildId);
        }
        return found;
    }

    /**
     * builder 가 다시 뜨면 진행 중이던 온프레미스 빌드를 다시 등록한다. 에이전트의 다음 단계를 받고,
     * 끝내 오지 않으면 {@link #expire} 가 제한 시간 뒤에 닫는다 (등록하지 않으면 BUILDING 에 영원히 남는다).
     * 기동할 때 {@link DeployResume} 가 부른다
     */
    void resumeInFlight() {
        int resumed = 0;
        for (Build build : store.findAll()) {
            String key = agentKey(build);
            if (key != null && inFlight(build) && running.putIfAbsent(build.getId(), key) == null) {
                resumed++;
            }
        }
        if (resumed > 0) {
            log.info("onprem builds resumed after restart: {}", resumed);
        }
    }

    /**
     * 에이전트가 hello 를 보냈다. 같은 key 에 다른 agentId 면 에이전트 프로세스가 새로 뜬 것이다.
     * 잡은 에이전트 메모리에만 있어서 이전 프로세스로 보낸 진행 중 빌드는 다시 오지 않는다. 제한 시간을 기다리지 않고 바로 닫는다.
     * 같은 agentId 의 재연결(소켓만 끊겼다 붙음)은 잡이 계속 돌고 있으니 그대로 둔다
     */
    public void agentHello(String agentKey, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return;
        }
        // 잡 보내기 직전 저장과 겹치지 않게 한다. 여기서 닫으면 send 가 cancelled 로 보고 잡을 보내지 않는다
        synchronized (transitions) {
            running.forEach((id, key) -> {
                if (!agentKey.equals(key)) {
                    return;
                }
                store.find(id).ifPresent(build -> {
                    String sentTo = sentTo(build);
                    if (inFlight(build) && sentTo != null && !sentTo.equals(agentId)) {
                        fail(build, "agent: 에이전트가 다시 시작돼 잡이 사라졌다 (" + sentTo + " → " + agentId
                                + "). 다시 배포한다");
                    }
                });
            });
        }
    }

    /** 잡을 보낸 에이전트 프로세스 id. 아직 보내기 전이거나 hello 전에 보냈으면 null */
    private static String sentTo(Build build) {
        String agentId = null;
        for (String line : build.getLogs()) {
            if (line.startsWith(SENT_TO)) {
                agentId = line.substring(SENT_TO.length()).trim();
            }
        }
        return agentId == null || agentId.isBlank() || "null".equals(agentId) ? null : agentId;
    }

    private static boolean inFlight(Build build) {
        return build.getStatus() == Build.Status.QUEUED || build.getStatus() == Build.Status.BUILDING
                || build.getStatus() == Build.Status.DEPLOYING;
    }

    /** 에이전트가 끊기거나 잡을 거절하면 상태가 오지 않는다. 빌드 제한 시간이 지나면 실패로 닫는다 */
    @Scheduled(fixedDelay = 30_000)
    void expire() {
        Instant limit = Instant.now().minus(Duration.ofSeconds(props.buildTimeoutSeconds()));
        running.keySet().forEach(id -> {
            synchronized (transitions) {
                if (!running.containsKey(id)) {
                    return;
                }
                store.find(id).ifPresentOrElse(build -> {
                    if (build.getUpdatedAt().isBefore(limit)) {
                        running.remove(id);
                        fail(build, "agent: " + props.buildTimeoutSeconds() + "초 동안 응답이 없다");
                    }
                }, () -> running.remove(id));
            }
        });
    }

    /** lily-on-premise DeployJob */
    static Map<String, Object> job(String id, BuildRequest request, String database) {
        return job(id, request, database, null, Map.of(), null);
    }

    /**
     * @param dockerfile builder 가 만든 Dockerfile. null 이면 보내지 않는다 (에이전트가 레포의 Dockerfile 을 쓴다)
     */
    static Map<String, Object> job(String id, BuildRequest request, String database, String dockerfile) {
        return job(id, request, database, dockerfile, Map.of(), request.canaryPath());
    }

    static Map<String, Object> job(String id, BuildRequest request, String database, String dockerfile,
                                   Map<String, String> migrations, String canaryPath) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("type", "job");
        job.put("id", id);
        job.put("repoUrl", request.repoUrl());
        job.put("branch", request.branchOrDefault());
        job.put("token", request.token() == null ? "" : request.token());
        job.put("appName", request.appName());
        job.put("targetPort", request.targetPortOrDefault());
        job.put("healthPath", request.readinessPath() == null || request.readinessPath().isBlank()
                ? DEFAULT_HEALTH_PATH : request.readinessPath());
        job.put("rootDir", request.rootDir() == null ? "" : request.rootDir());
        job.put("env", request.env() == null ? Map.of() : request.env());
        job.put("database", database);
        if (dockerfile != null) {
            job.put("dockerfile", dockerfile);
        }
        job.put("migrations", migrations == null ? Map.of() : migrations);
        if (canaryPath != null && !canaryPath.isBlank()) {
            job.put("canaryPath", canaryPath);
        }
        return job;
    }

    private void update(Build build, Build.Status status, String line) {
        build.status(status, line);
        store.save(build);
    }

    private void fail(Build build, String reason) {
        log.warn("onprem build failed: id={} app={} reason={}", build.getId(), build.getAppName(), reason);
        running.remove(build.getId());
        diagnose(build, "failed: " + reason);
        update(build, Build.Status.FAILED, "failed: " + reason);
    }

    private FailureDiagnoser diagnoser = BuildService.offlineDiagnoser();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void diagnoser(FailureDiagnoser diagnoser) {
        this.diagnoser = diagnoser;
    }

    private EdgeWorker edge = EdgeWorker.disabled();
    private EdgePrewarm prewarm;
    private EdgeQueue queue = EdgeQueue.disabled();
    static final String EDGE_SNAPSHOT = "edgeSnapshot=";
    static final String EDGE_QUEUE = "edgeQueue=";

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void edgeQueue(EdgeQueue queue) {
        this.queue = queue;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void edgeWorker(EdgeWorker edge, EdgePrewarm prewarm) {
        this.edge = edge;
        this.prewarm = prewarm;
    }

    /**
     * 온프레미스 배포가 끝났다. 공개 주소에 엣지 Worker 라우트를 걸고 주요 페이지를 열어 읽기 사본을 채운다.
     * 대기 배포가 없는 앱(DB 가 PC 에 있는 앱, 온프레미스 전용)도 PC 장애 때 사본으로 GET 에 답하게 된다. 실패해도 배포는 성공이다.
     * 배포 화면의 엣지 체크박스를 앱 DO 설정에 넣는다: 쓰기 큐 켜기 = 모든 POST(/), 끄기 = 없음. 읽기 사본을 끄면 prewarm 도 하지 않는다
     */
    private void warmEdge(Build build) {
        if (!edge.enabled()) {
            return;
        }
        String app = build.getAppName();
        runner.run(() -> {
            try {
                note(build, edge.attachRoute(app));
            } catch (RuntimeException e) {
                log.warn("edge route {} failed: {}", app, e.getMessage());
                note(build, "edge: failed " + e.getMessage());
                return;
            }
            Boolean snapshot = flag(build, EDGE_SNAPSHOT);
            Boolean writes = flag(build, EDGE_QUEUE);
            if (queue.enabled() && (snapshot != null || writes != null)) {
                try {
                    queue.configure(app, writes == null ? null : (writes ? List.of("/") : List.of()), snapshot);
                    note(build, "edge: options" + (snapshot == null ? "" : " read copy " + (snapshot ? "on" : "off"))
                            + (writes == null ? "" : " write queue " + (writes ? "on" : "off")));
                } catch (RuntimeException e) {
                    log.warn("edge options {} failed: {}", app, e.getMessage());
                    note(build, "edge: options failed " + e.getMessage());
                }
            }
            if (Boolean.FALSE.equals(snapshot)) {
                note(build, "prewarm: skipped (read copy off)");
            } else if (prewarm != null) {
                note(build, prewarm.warm(edge.publicUrl(app)));
            }
        });
    }

    /** start 가 남긴 엣지 체크박스 값. 없으면 null */
    private static Boolean flag(Build build, String prefix) {
        return build.getLogs().stream().filter(line -> line.startsWith(prefix)).findFirst()
                .map(line -> "on".equals(line.substring(prefix.length()))).orElse(null);
    }

    /** 끝난 빌드에 한 줄 덧붙인다 */
    private void note(Build build, String line) {
        synchronized (transitions) {
            build.log(line);
            store.save(build);
        }
    }

    /** FAILED 로 바꾸기 전에 원인을 정한다 (화면이 FAILED 를 보자마자 가져간다) */
    private void diagnose(Build build, String lastLine) {
        try {
            java.util.List<String> logs = new java.util.ArrayList<>(build.getLogs());
            logs.add(lastLine);
            build.diagnosis(diagnoser.diagnose(logs, new FailureDiagnoser.Context(null, build.getDatabase(), null),
                    java.util.List.of()));
        } catch (RuntimeException e) {
            log.warn("onprem diagnosis failed: id={} message={}", build.getId(), e.getMessage());
        }
    }
}
