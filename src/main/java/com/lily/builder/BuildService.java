package com.lily.builder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 폼 입력 → 커밋 고정·마이그레이션 수집 → Kaniko 빌드 → lily-cicd 배포.
 * 단계가 바뀔 때마다 배포 이력 저장소에 기록한다. 화면은 저장소를 조회한다.
 */
@Service
public class BuildService {

    private static final Logger log = LoggerFactory.getLogger(BuildService.class);
    /** 헬스 경로 대신 보내면 lily-cicd·에이전트가 HTTP 대신 포트가 열렸는지만 본다 */
    static final String TCP_HEALTH = "tcp";
    private static final DateTimeFormatter TAG = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    private final BuildStore store;
    private final KanikoBuilder kaniko;
    private final CicdClient cicd;
    private final BuildRunner runner;
    private final EcrRepositories ecr;
    private final GitHubSource github;
    private final DeployFollow follow;

    @Autowired
    public BuildService(BuildStore store, KanikoBuilder kaniko, CicdClient cicd, BuildRunner runner,
                        EcrRepositories ecr, GitHubSource github) {
        this(store, kaniko, cicd, runner, ecr, github, new DeployFollow(cicd));
    }

    BuildService(BuildStore store, KanikoBuilder kaniko, CicdClient cicd, BuildRunner runner,
                 EcrRepositories ecr, GitHubSource github, DeployFollow follow) {
        this.store = store;
        this.kaniko = kaniko;
        this.cicd = cicd;
        this.runner = runner;
        this.ecr = ecr;
        this.github = github;
        this.follow = follow;
    }

    public Build start(BuildRequest request) {
        Build build = new Build(UUID.randomUUID().toString().substring(0, 8), request);
        build.log("queued: " + request.repoUrl() + " branch=" + build.getBranch()
                + (build.getRootDir() == null ? "" : " dir=" + build.getRootDir()));
        store.save(build);
        runner.run(() -> execute(build, request));
        return build;
    }

    public Optional<Build> get(String id) {
        return store.find(id);
    }

    /** 배포 이력. 최신순 */
    public List<Build> history() {
        return store.findAll();
    }

    /**
     * 프로세스가 죽어서 DEPLOYING 으로 남은 클라우드 배포를 진행 상태로 닫는다.
     * 온프레미스 배포와 이미지가 없는 빌드는 건드리지 않는다. POST 는 보내지 않는다.
     */
    void resumeInterrupted() {
        for (Build build : store.findAll()) {
            if (build.getStatus() != Build.Status.DEPLOYING || build.getImage() == null) {
                continue;
            }
            if (build.getLogs().stream().noneMatch(line -> line.startsWith("deploy: lily-cicd"))) {
                continue;
            }
            runner.run(() -> finishFromProgress(build));
        }
    }

    private void finishFromProgress(Build build) {
        build.log("deploy: builder restarted, following progress");
        store.save(build);
        try {
            DeployFollow.Outcome outcome = follow.await(build, build.getCreatedAt());
            if (outcome.rolledBackReason() != null) {
                rolledBack(build, outcome.rolledBackReason());
                return;
            }
            CicdClient.Result result = outcome.success();
            if (result != null && result.logs() != null) {
                result.logs().forEach(line -> build.log("cicd: " + line));
            }
            build.url(result == null ? null : result.targetHostUrl());
            update(build, Build.Status.SUCCEEDED, "done: " + build.getUrl());
        } catch (RuntimeException e) {
            fail(build, e.getMessage());
        }
    }

    void execute(Build build, BuildRequest request) {
        String tag = ZonedDateTime.now(ZoneOffset.UTC).format(TAG);
        try {
            String commit = github.resolveCommit(request);
            build.log("source: commit " + commit);
            Source source = source(build, request, commit);
            String dockerfile = source.dockerfile();
            request = detect(build, source.request(), commit, dockerfile, source.detectDir());
            Map<String, String> migrations = github.migrations(request, commit);
            build.log(migrations.isEmpty()
                    ? "source: migrations none (" + (request.migrateOrDefault() ? GitHubSource.folder(request) : "migrate=false") + ")"
                    : "source: migrations " + migrations.keySet());
            if (ecr.ensure(request.appName())) {
                build.log("build: created ecr repository " + request.appName());
            }
            update(build, Build.Status.BUILDING, "build: kaniko job build-" + build.getId());
            String image = kaniko.build(build.getId(), request, tag, commit, dockerfile);
            build.image(image);
            build.log("build: pushed " + image);

            update(build, Build.Status.DEPLOYING, "deploy: lily-cicd"
                    + (build.getDatabase() == null ? "" : " database=" + build.getDatabase()));
            Instant started = Instant.now();
            CicdClient.Result result;
            try (ProgressWatch ignored = watchProgress(build)) {
                try {
                    result = cicd.deploy(request, image, tag, migrations);
                } catch (RestClientResponseException e) {
                    throw e;
                } catch (RestClientException e) {
                    // 응답이 없다는 것은 실패가 아니다. cicd 가 아직 배포 중일 수 있다. POST 는 다시 보내지 않는다
                    build.log("deploy: response lost, following progress");
                    store.save(build);
                    DeployFollow.Outcome outcome = follow.await(build, started);
                    if (outcome.rolledBackReason() != null) {
                        rolledBack(build, outcome.rolledBackReason());
                        return;
                    }
                    result = outcome.success();
                }
            }
            if (result != null && result.logs() != null) {
                result.logs().forEach(line -> build.log("cicd: " + line));
            }
            build.url(result == null ? null : result.targetHostUrl());
            if (request.isStandby()) {
                // 클라우드 버스팅 대기: 이미지·Ingress·DB 는 준비해 두고 Pod 만 0 으로
                cicd.scale(request.appName(), 0);
                build.log("standby: scaled to 0");
            }
            update(build, Build.Status.SUCCEEDED, "done: " + build.getUrl());
        } catch (RestClientResponseException e) {
            CicdClient.DeployError error = deployError(e);
            if (e.getStatusCode().value() == 422 && error != null && "ROLLED_BACK".equals(error.status())) {
                // canary 판정 실패: 새 버전은 지워졌고 트래픽은 이전 버전 그대로다
                if (error.logs() != null) {
                    error.logs().forEach(line -> build.log("cicd: " + line));
                }
                rolledBack(build, error.message());
                return;
            }
            fail(build, "cicd " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            fail(build, e.getMessage());
        }
    }

     static final String NOTHING_TO_BUILD = "Dockerfile 이 없고 빌드 방법도 찾지 못했다"
            + " (pom.xml, build.gradle, package.json, requirements.txt, pyproject.toml, go.mod, index.html 이 없다)";

    /**
     * 빌드할 폴더와 Dockerfile.
     *
     * @param request    빌드할 폴더(rootDir)와 마이그레이션 폴더를 정한 요청
     * @param dockerfile 만든 Dockerfile. null 이면 레포 것
     * @param detectDir  포트·DB·actuator 를 볼 폴더 (rootDir 기준). null 이면 rootDir
     */
    record Source(BuildRequest request, String dockerfile, String detectDir) {
    }

    /**
     * 레포에 Dockerfile 이 없으면 빌드 파일을 보고 만든다.
     *
     * <p>화면은 레포 주소만 받는다. 폴더를 지정하지 않았는데 루트에 빌드 파일이 없으면 최상위 폴더를 본다.
     * 앱 폴더가 하나면 그 폴더를 빌드하고, 서버 앱과 정적 프론트가 하나씩이면 한 이미지로 묶는다
     * ({@link DockerfileGenerator#combine}). backend/ 와 frontend/ 가 한 레포에 있는 프로젝트가 주소 하나로 뜬다.
     *
     * @throws IllegalStateException 빌드할 앱을 못 찾았거나 하나로 정할 수 없을 때
     */
    Source source(Build build, BuildRequest request, String commit) {
        java.util.Set<String> envKeys = request.env() == null ? java.util.Set.of() : request.env().keySet();
        Folder root = folder(request, commit, envKeys);
        if (root.found()) {
            if (root.generated() != null) {
                build.log("source: no Dockerfile, generated for " + root.generated().stack());
            }
            return new Source(request, root.dockerfile(), null);
        }
        if (request.rootDir() != null && !request.rootDir().isBlank()) {
            throw new IllegalStateException(NOTHING_TO_BUILD + ". 폴더 " + request.rootDir() + " 를 확인한다");
        }

        List<Folder> apps = new java.util.ArrayList<>();
        for (String dir : github.folders(request, commit)) {
            Folder folder = folder(request.withSource(dir, request.migrationsPath(), request.targetPort()), commit, envKeys);
            if (folder.found()) {
                apps.add(folder);
            }
        }
        if (apps.isEmpty()) {
            throw new IllegalStateException(NOTHING_TO_BUILD + ". 루트와 최상위 폴더 모두에 없다");
        }
        if (apps.size() == 1) {
            Folder app = apps.getFirst();
            build.log("source: app in folder " + app.dir() + (app.generated() == null
                    ? " (Dockerfile)" : ", no Dockerfile, generated for " + app.generated().stack()));
            return new Source(app.request(), app.dockerfile(), null);
        }

        List<Folder> statics = apps.stream().filter(f -> f.generated() != null && f.generated().isStatic()).toList();
        List<Folder> servers = apps.stream().filter(f -> f.generated() != null && !f.generated().isStatic()).toList();
        if (apps.size() == 2 && statics.size() == 1 && servers.size() == 1) {
            Folder backend = servers.getFirst();
            Folder frontend = statics.getFirst();
            DockerfileGenerator.Generated combined = DockerfileGenerator.combine(
                    backend.dir(), backend.generated(), frontend.dir(), frontend.generated());
            build.log("source: backend " + backend.dir() + " + frontend " + frontend.dir()
                    + " in one image, generated for " + combined.stack());
            String migrations = backend.dir() + "/" + (request.migrationsPath() == null || request.migrationsPath().isBlank()
                    ? GitHubSource.DEFAULT_MIGRATIONS_PATH : request.migrationsPath());
            // 포트는 앞의 프록시 포트(EXPOSE)로 다시 정한다
            return new Source(request.withSource(null, migrations, null), combined.dockerfile(), backend.dir());
        }
        throw new IllegalStateException("앱 폴더가 여러 개라 하나로 정하지 못했다 ("
                + apps.stream().map(f -> f.dir() + " " + (f.generated() == null ? "Dockerfile" : f.generated().stack()))
                        .collect(java.util.stream.Collectors.joining(", "))
                + "). 배포할 폴더를 지정한다");
    }

    /** 폴더 하나를 본 결과. Dockerfile 이 있으면 generated 는 null */
    private record Folder(BuildRequest request, boolean hasDockerfile, DockerfileGenerator.Generated generated) {
        boolean found() {
            return hasDockerfile || generated != null;
        }

        String dir() {
            return request.rootDir();
        }

        String dockerfile() {
            return generated == null ? null : generated.dockerfile();
        }
    }

    private Folder folder(BuildRequest request, String commit, java.util.Set<String> envKeys) {
        if (github.file(request, commit, "Dockerfile") != null) {
            return new Folder(request, true, null);
        }
        return new Folder(request, false, DockerfileGenerator.generate(path -> github.file(request, commit, path), envKeys));
    }
    /**
     * 비어 있거나 auto 인 포트·DB·헬스 경로를 레포 파일로 정한다. 요청에 값이 있으면 그 값을 쓴다.
     * 레포 주소만 받는 화면에서 포트가 8080 이 아닌 앱, DB 가 필요 없는 앱도 배포되게 하려고 둔다.
     * 온프레미스 배포({@link AgentDeployService})도 같은 값으로 잡을 만든다.
     */
    BuildRequest detect(Build build, BuildRequest request, String commit) {
        return detect(build, request, commit, null);
    }

    /**
     * @param dockerfile 만든 Dockerfile. 레포의 Dockerfile 대신 이걸로 포트를 찾는다. null 이면 레포 것
     */
    BuildRequest detect(Build build, BuildRequest request, String commit, String dockerfile) {
        return detect(build, request, commit, dockerfile, null);
    }

    /**
     * @param detectDir DB·actuator 를 볼 폴더 (rootDir 기준). 백엔드와 프론트를 묶은 이미지면 백엔드 폴더. null 이면 rootDir
     */
    BuildRequest detect(Build build, BuildRequest request, String commit, String dockerfile, String detectDir) {
        if (!request.needsDetection()) {
            return request;
        }
        AppDetector.Result found = detectFiles(request, commit, dockerfile, detectDir);

        int port;
        if (request.targetPort() != null) {
            port = request.targetPort();
        } else if (found.port() != null) {
            port = found.port();
            build.log("detect: port " + port + " (" + found.portSource() + ")");
        } else {
            port = BuildRequest.DEFAULT_TARGET_PORT;
            build.log("detect: port " + port + " (default, no EXPOSE)");
        }

        String database = request.database();
        if (request.autoDatabase()) {
            database = found.database();
            build.log(database == null ? "detect: database none (no driver found)"
                    : "detect: database " + database + " (" + found.databaseSource() + ")");
            build.database(database);
        }

        // actuator 가 없으면 lily-cicd 기본 경로(/actuator/health/*)가 404 라 Ready 가 되지 않는다.
        // / 로 보면 Spring Security 앱(401·403)이나 / 가 없는 API 서버(404)가 떨어지므로 포트가 열렸는지만 본다
        String readiness = request.readinessPath();
        String liveness = request.livenessPath();
        if (!found.actuator() && (isBlank(readiness) || isBlank(liveness))) {
            readiness = isBlank(readiness) ? TCP_HEALTH : readiness;
            liveness = isBlank(liveness) ? TCP_HEALTH : liveness;
            build.log("detect: health " + (TCP_HEALTH.equals(readiness) ? "tcp port " + port : readiness)
                    + " (no spring actuator)");
        }
        return request.withDetected(port, database, readiness, liveness);
    }

    private AppDetector.Result detectFiles(BuildRequest request, String commit, String dockerfile, String detectDir) {
        return AppDetector.detect(path -> dockerfile != null && path.equals("Dockerfile")
                ? dockerfile : github.file(request, commit, detectDir == null ? path : detectDir + "/" + path));
    }

    /**
     * 배포하기 전에 레포가 쓰는 DB 를 본다. 빌드는 하지 않는다.
     * 화면이 "PostgreSQL 이 맞나요?" 처럼 사용자에게 확인받고, 고른 값을 {@link BuildRequest#database()} 로 보낸다.
     * 폴더는 실제 배포와 같은 방법({@link #source})으로 찾는다.
     *
     * @throws IllegalStateException 브랜치나 빌드할 앱을 찾지 못했을 때
     */
    public Detection inspect(DetectRequest detect) {
        BuildRequest request = new BuildRequest(detect.repoUrl(), detect.branch(), detect.token(), detect.rootDir(),
                "detect", null, "auto", null, null, Map.of());
        String commit = github.resolveCommit(request);
        Source source = source(new Build("detect", request), request, commit);
        AppDetector.Result found = detectFiles(source.request(), commit, source.dockerfile(), source.detectDir());
        String dir = source.detectDir() != null ? source.detectDir() : source.request().rootDir();
        return new Detection(found.database(), found.databaseSource(), isBlank(dir) ? null : dir);
    }

    /**
     * @param database       postgres / mysql. 드라이버가 안 보이면 null
     * @param databaseSource 근거. 예: {@code build.gradle: org.postgresql}
     * @param dir            본 폴더 (레포 루트 기준). 루트면 null
     */
    public record Detection(String database, String databaseSource, String dir) {
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * lily-cicd 배포 요청은 끝날 때까지 응답하지 않는다. 그동안 1초마다 진행 단계를 물어 로그에 남긴다.
     * 이번 배포를 시작한 뒤에 바뀐 단계만 남긴다.
     */
    private ProgressWatch watchProgress(Build build) {
        Instant since = Instant.now();
        ProgressWatch watch = new ProgressWatch();
        watch.thread = Thread.ofVirtual().name("progress-" + build.getId()).start(() -> {
            String last = null;
            while (!watch.done) {
                try {
                    CicdClient.Progress progress = cicd.progress(build.getAppName());
                    if (progress != null && progress.updatedAt() != null && !progress.updatedAt().isBefore(since)) {
                        String line = "progress: " + progress.stage() + " " + progress.detail();
                        if (!line.equals(last)) {
                            last = line;
                            build.log(line);
                            store.save(build);
                        }
                    }
                } catch (RuntimeException e) {
                    log.debug("progress poll failed: id={} message={}", build.getId(), e.getMessage());
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        return watch;
    }

    private static final class ProgressWatch implements AutoCloseable {
        private volatile boolean done;
        private Thread thread;

        @Override
        public void close() {
            done = true;
            if (thread != null) {
                thread.interrupt();
                try {
                    thread.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static CicdClient.DeployError deployError(RestClientResponseException e) {
        try {
            return JSON.readValue(e.getResponseBodyAsString(), CicdClient.DeployError.class);
        } catch (Exception parse) {
            return null;
        }
    }

    private void rolledBack(Build build, String reason) {
        log.info("canary rejected: id={} app={} reason={}", build.getId(), build.getAppName(), reason);
        try {
            update(build, Build.Status.ROLLED_BACK, "rolled back: " + reason);
        } catch (RuntimeException e) {
            log.error("could not record rolled back build: id={} reason={}", build.getId(), e.getMessage());
        }
    }

    private void update(Build build, Build.Status status, String line) {
        build.status(status, line);
        store.save(build);
    }

    private void fail(Build build, String reason) {
        log.warn("build failed: id={} app={} reason={}", build.getId(), build.getAppName(), reason);
        try {
            update(build, Build.Status.FAILED, "failed: " + reason);
        } catch (RuntimeException e) {
            log.error("could not record failed build: id={} reason={}", build.getId(), e.getMessage());
        }
    }

    /** 테스트에서 동기로 돌릴 수 있게 비동기 실행만 분리한다 */
    @Service
    public static class BuildRunner {
        @Async
        public void run(Runnable task) {
            task.run();
        }
    }
}
