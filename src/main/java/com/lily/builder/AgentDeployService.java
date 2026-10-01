package com.lily.builder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private static final Logger log = LoggerFactory.getLogger(AgentDeployService.class);

    private final BuildStore store;
    private final GitHubSource github;
    private final BuildService builds;
    private final AgentHub hub;
    private final BuildService.BuildRunner runner;
    private final BuilderProperties props;
    private final ObjectMapper json = new ObjectMapper();
    /** 진행 중인 온프레미스 빌드 id → 에이전트 key. 다른 에이전트가 보낸 상태는 받지 않는다 */
    private final Map<String, String> running = new ConcurrentHashMap<>();
    /** 롤백·거점 전환 id → 에이전트가 SUCCEEDED/FAILED 를 보낼 때까지 기다리는 응답 */
    private final Map<String, CompletableFuture<String>> rollbackWaiters = new ConcurrentHashMap<>();

    public AgentDeployService(BuildStore store, GitHubSource github, BuildService builds, AgentHub hub,
                              BuildService.BuildRunner runner, BuilderProperties props) {
        this.store = store;
        this.github = github;
        this.builds = builds;
        this.hub = hub;
        this.runner = runner;
        this.props = props;
    }

    /** @throws IllegalArgumentException 에이전트 규칙에 맞지 않는 앱 이름 */
    public Build start(String agentKey, BuildRequest request) {
        if (!APP_NAME.matcher(request.appName()).matches()) {
            throw new IllegalArgumentException("온프레미스 앱 이름은 영문 소문자로 시작하고 31자 이하여야 한다: " + request.appName());
        }
        Build build = new Build(UUID.randomUUID().toString().substring(0, 8), request);
        build.log("queued: " + request.repoUrl() + " branch=" + build.getBranch() + " target=onprem agent=" + agentKey);
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
            build.log("source: commit " + commit);
            // 클라우드와 같은 Dockerfile 을 쓴다. 레포에 있으면 null 이라 에이전트가 레포 것을 쓴다
            BuildService.Source source = builds.source(build, request, commit);
            String dockerfile = source.dockerfile();
            BuildRequest resolved = builds.detect(build, source.request(), commit, dockerfile, source.detectDir());

            String database = resolved.database();
            if (database != null && !database.isBlank() && !hub.supportsDatabase(agentKey)) {
                // DB 없이 보내면 앱이 DB 에 붙으려다 기동하지 못하고, 헬스 체크 제한 시간(3분) 뒤에야 실패한다.
                // (lily-blog-sample 은 이미지가 prod 프로파일로 고정이라 내장 DB 로 뜨지 않는다) 보내기 전에 이유와 함께 끝낸다
                throw new IllegalStateException("이 앱은 DB(" + database + ")가 필요한데 내 PC 에이전트에 DB 터널이 없다."
                        + " 에이전트 PC 의 lily-on-premise/test/burst 에 burst.env 와 keys/ 를 두고 다시 실행한다");
            }
            // 보낸 뒤에 기록하면 에이전트가 먼저 보낸 BUILDING 을 덮을 수 있다. 보내기 전에 남긴다
            build.log("agent: send to " + hub.agentId(agentKey));
            store.save(build);
            Map<String, String> migrations = github.migrations(resolved, commit);
            hub.send(agentKey, json.writeValueAsString(
                    job(build.getId(), resolved, database, dockerfile, migrations, resolved.canaryPath())));
        } catch (RuntimeException | JsonProcessingException e) {
            fail(build, e.getMessage());
        }
    }

    /**
     * 이 앱의 최근 성공이 온프레미스 배포면 그 에이전트의 직전 슬롯으로 되돌린다.
     * @return 롤백 결과 JSON. 최근 성공이 클라우드면 empty (호출자가 lily-cicd 로 넘긴다)
     */
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
                    "schema", "unchanged",
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
        String id = "h" + UUID.randomUUID().toString().replace("-", "").substring(0, 7);
        CompletableFuture<String> done = new CompletableFuture<>();
        rollbackWaiters.put(id, done);
        try {
            hub.send(key.get(), json.writeValueAsString(Map.of(
                    "type", "home", "app", app, "home", target, "id", id)));
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

    private static String agentKey(Build build) {
        String key = null;
        for (String line : build.getLogs()) {
            int at = line.indexOf("target=onprem agent=");
            if (at >= 0) {
                key = line.substring(at + "target=onprem agent=".length()).trim();
            }
        }
        return key == null || key.isBlank() ? null : key;
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
        if (!agentKey.equals(running.get(buildId))) {
            log.debug("agent status ignored: key={} id={}", agentKey, buildId);
            return;
        }
        Optional<Build> found = store.find(buildId);
        if (found.isEmpty()) {
            running.remove(buildId);
            return;
        }
        Build build = found.get();
        String logLine = "agent: " + status + (line == null || line.isBlank() ? "" : " " + line);
        switch (status) {
            case "SUCCEEDED" -> {
                running.remove(buildId);
                build.url(url == null || url.isBlank() ? null : url);
                update(build, Build.Status.SUCCEEDED, logLine);
            }
            case "FAILED" -> {
                running.remove(buildId);
                update(build, Build.Status.FAILED, logLine);
            }
            case "BUILDING" -> update(build, Build.Status.BUILDING, logLine);
            case "STARTING", "HEALTH", "JUDGING", "SWITCHING" -> update(build, Build.Status.DEPLOYING, logLine);
            default -> update(build, build.getStatus(), logLine);
        }
    }

    /** 에이전트가 끊기거나 잡을 거절하면 상태가 오지 않는다. 빌드 제한 시간이 지나면 실패로 닫는다 */
    @Scheduled(fixedDelay = 30_000)
    void expire() {
        Instant limit = Instant.now().minus(Duration.ofSeconds(props.buildTimeoutSeconds()));
        running.keySet().forEach(id -> store.find(id).ifPresentOrElse(build -> {
            if (build.getUpdatedAt().isBefore(limit)) {
                running.remove(id);
                fail(build, "agent: " + props.buildTimeoutSeconds() + "초 동안 응답이 없다");
            }
        }, () -> running.remove(id)));
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
        update(build, Build.Status.FAILED, "failed: " + reason);
    }
}
