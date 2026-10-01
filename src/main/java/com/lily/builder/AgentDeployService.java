package com.lily.builder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 온프레미스 배포. 빌드·실행은 사용자 PC 의 에이전트가 하고, 여기서는 잡을 보내고 에이전트가 보내는 단계를 Build 로 기록한다.
 * 클라우드 배포와 같은 Build 로 남기므로 lily-frontend 는 GET /api/builds/{id} 로 똑같이 상태를 본다.
 *
 * <pre>
 * 에이전트 상태      Build 상태
 * QUEUED·CHECKOUT·ANALYZE      QUEUED (레포 확인)
 * BUILDING                     BUILDING
 * STARTING·HEALTH·SWITCHING    DEPLOYING
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
            String dockerfile = builds.dockerfile(build, request, commit);
            BuildRequest resolved = builds.detect(build, request, commit, dockerfile);

            String database = resolved.database();
            if (database != null && !database.isBlank() && !hub.supportsDatabase(agentKey)) {
                // DB 없이 보내면 앱이 DB 에 붙으려다 기동하지 못하고, 헬스 체크 제한 시간(2분) 뒤에야 실패한다.
                // (lily-blog-sample 은 이미지가 prod 프로파일로 고정이라 내장 DB 로 뜨지 않는다) 보내기 전에 이유와 함께 끝낸다
                throw new IllegalStateException("이 앱은 DB(" + database + ")가 필요한데 내 PC 에이전트에 DB 터널이 없다."
                        + " 에이전트 PC 의 lily-on-premise/test/burst 에 burst.env 와 keys/ 를 두고 다시 실행한다");
            }
            // 보낸 뒤에 기록하면 에이전트가 먼저 보낸 BUILDING 을 덮을 수 있다. 보내기 전에 남긴다
            build.log("agent: send to " + hub.agentId(agentKey));
            store.save(build);
            hub.send(agentKey, json.writeValueAsString(job(build.getId(), resolved, database, dockerfile)));
        } catch (RuntimeException | JsonProcessingException e) {
            fail(build, e.getMessage());
        }
    }

    /** 에이전트가 보낸 단계. 이 에이전트로 보낸 빌드만 받는다 */
    public void agentStatus(String agentKey, String buildId, String status, String line, String url) {
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
            case "STARTING", "HEALTH", "SWITCHING" -> update(build, Build.Status.DEPLOYING, logLine);
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
        return job(id, request, database, null);
    }

    /**
     * @param dockerfile builder 가 만든 Dockerfile. null 이면 보내지 않는다 (에이전트가 레포의 Dockerfile 을 쓴다)
     */
    static Map<String, Object> job(String id, BuildRequest request, String database, String dockerfile) {
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
