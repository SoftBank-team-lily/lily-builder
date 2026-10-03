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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final ConfigAdvisor config;
    private final FailureDiagnoser diagnoser;
    private final AppAddress addresses;
    private final EdgeWorker edge;
    /**
     * 실행 중인 클라우드 빌드. 취소는 실행 스레드와 같은 객체의 상태를 바꾼다 (저장소에서 새로 읽은 객체로 바꾸면
     * 실행 스레드가 다음에 저장할 때 CANCELLED 를 덮는다)
     */
    private final Map<String, Build> live = new ConcurrentHashMap<>();
    /** 취소와 다음 단계로 넘어가기를 한 번에 하나씩. 취소한 빌드가 lily-cicd 로 넘어가지 않게 한다 */
    private final Object transitions = new Object();
    private final CloudClients clouds;

    @Autowired
    public BuildService(BuildStore store, KanikoBuilder kaniko, CicdClient cicd, BuildRunner runner,
                        EcrRepositories ecr, GitHubSource github, ConfigAdvisor config, FailureDiagnoser diagnoser,
                        AppAddress addresses, EdgeWorker edge, CloudClients clouds) {
        this(store, kaniko, cicd, runner, ecr, github, new DeployFollow(cicd), config, diagnoser, addresses, edge, clouds);
    }

    BuildService(BuildStore store, KanikoBuilder kaniko, CicdClient cicd, BuildRunner runner,
                 EcrRepositories ecr, GitHubSource github) {
        this(store, kaniko, cicd, runner, ecr, github, new DeployFollow(cicd));
    }

    /** AI 없이 규칙으로만 판단한다 (테스트) */
    BuildService(BuildStore store, KanikoBuilder kaniko, CicdClient cicd, BuildRunner runner,
                 EcrRepositories ecr, GitHubSource github, DeployFollow follow) {
        this(store, kaniko, cicd, runner, ecr, github, follow, offlineConfig(), offlineDiagnoser(),
                AppAddress.disabled());
    }

    BuildService(BuildStore store, KanikoBuilder kaniko, CicdClient cicd, BuildRunner runner,
                 EcrRepositories ecr, GitHubSource github, DeployFollow follow,
                 ConfigAdvisor config, FailureDiagnoser diagnoser, AppAddress addresses) {
        this(store, kaniko, cicd, runner, ecr, github, follow, config, diagnoser, addresses, EdgeWorker.disabled());
    }

    BuildService(BuildStore store, KanikoBuilder kaniko, CicdClient cicd, BuildRunner runner,
                 EcrRepositories ecr, GitHubSource github, DeployFollow follow,
                 ConfigAdvisor config, FailureDiagnoser diagnoser, AppAddress addresses, EdgeWorker edge) {
        this(store, kaniko, cicd, runner, ecr, github, follow, config, diagnoser, addresses, edge, null);
    }

    BuildService(BuildStore store, KanikoBuilder kaniko, CicdClient cicd, BuildRunner runner,
                 EcrRepositories ecr, GitHubSource github, DeployFollow follow,
                 ConfigAdvisor config, FailureDiagnoser diagnoser, AppAddress addresses, EdgeWorker edge,
                 CloudClients clouds) {
        this.clouds = clouds;
        this.addresses = addresses;
        this.edge = edge;
        this.config = config;
        this.diagnoser = diagnoser;
        this.store = store;
        this.kaniko = kaniko;
        this.cicd = cicd;
        this.runner = runner;
        this.ecr = ecr;
        this.github = github;
        this.follow = follow;
    }

    public Build start(BuildRequest request) {
        return start(request, null);
    }

    /** @param note 큐에 넣을 때 같이 남길 줄 (예: 대기 배포를 요청한 에이전트) */
    public Build start(BuildRequest request, String note) {
        Build build = new Build(UUID.randomUUID().toString().substring(0, 8), request);
        build.log("queued: " + request.repoUrl() + " branch=" + build.getBranch()
                + (build.getRootDir() == null ? "" : " dir=" + build.getRootDir()));
        build.log("deploymentMode=" + request.deploymentModeOrDefault());
        build.log("cloudProvider=" + request.cloudProviderOrDefault());
        if (note != null && !note.isBlank()) {
            build.log(note);
        }
        store.save(build);
        live.put(build.getId(), build);
        runner.run(() -> {
            try {
                execute(build, request);
            } finally {
                live.remove(build.getId());
            }
        });
        return build;
    }

    /**
     * 클라우드 빌드를 멈춘다. lily-cicd 로 넘기기 전(QUEUED·BUILDING)만 된다. 빌드 중이면 Kaniko Job 을 지운다.
     * 실행 스레드는 다음 단계로 넘어가려다 멈춘다.
     *
     * @throws NoSuchElementException 없는 빌드
     * @throws IllegalStateException  이미 끝났거나 lily-cicd 가 배포를 시작했다
     */
    public Build cancel(String id) {
        Build build;
        boolean building;
        synchronized (transitions) {
            build = live.get(id);
            if (build == null) {
                build = store.find(id).orElseThrow(() -> new NoSuchElementException(id));
            }
            switch (build.getStatus()) {
                case QUEUED, BUILDING -> {
                }
                case DEPLOYING -> throw new IllegalStateException("lily-cicd 가 새 버전을 띄우는 중이라 취소할 수 없다");
                default -> throw new IllegalStateException("이미 끝난 배포다: " + build.getStatus());
            }
            building = build.getStatus() == Build.Status.BUILDING;
            update(build, Build.Status.CANCELLED, "cancelled: 사용자가 취소했다");
        }
        log.info("build cancelled: id={} app={}", build.getId(), build.getAppName());
        if (building) {
            try {
                kaniko.cancel(id);
            } catch (RuntimeException e) {
                log.warn("kaniko job delete failed: id={} message={}", id, e.getMessage());
            }
        }
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
            boolean gcp = build.getLogs().stream().anyMatch(line -> line.startsWith("cloudProvider=GCP"));
            DeployFollow watching = follow;
            if (gcp) {
                if (clouds == null || !clouds.deployConfigured()) {
                    fail(build, CloudClients.MISSING);
                    return;
                }
                watching = new DeployFollow(clouds.cicd());
            }
            DeployFollow.Outcome outcome = watching.await(build, build.getCreatedAt());
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
        // 실패하면 어디까지 왔는지로 원인을 본다
        Attempt attempt = new Attempt(request);
        try {
            String commit = github.resolveCommit(request);
            attempt.commit = commit;
            build.commit(commit);
            build.log("source: commit " + commit);
            Source source = source(build, request, commit);
            String dockerfile = source.dockerfile();
            attempt.request = source.request();
            attempt.detectDir = source.detectDir();
            request = detect(build, source.request(), commit, dockerfile, source.detectDir());
            attempt.request = request;
            Map<String, String> migrations = github.migrations(request, commit);
            build.log(migrations.isEmpty()
                    ? "source: migrations none (" + (request.migrateOrDefault() ? GitHubSource.folder(request) : "migrate=false") + ")"
                    : "source: migrations " + migrations.keySet());
            String provider = request.cloudProviderOrDefault();
            boolean gcp = "GCP".equals(provider);
            CicdClient targetCicd = cicd;
            DeployFollow watching = follow;
            String registry = null;
            String authSecret = null;
            if (gcp) {
                if (clouds == null || !clouds.deployConfigured()) {
                    throw new IllegalStateException(CloudClients.MISSING);
                }
                targetCicd = clouds.cicd();
                watching = new DeployFollow(targetCicd);
                registry = clouds.registry();
                authSecret = clouds.registrySecret();
                build.log("build: registry " + registry);
            } else if (ecr.ensure(request.appName())) {
                build.log("build: created ecr repository " + request.appName());
            }
            advance(build, Build.Status.BUILDING, "build: kaniko job build-" + build.getId());
            String image = registry == null
                    ? kaniko.build(build.getId(), request, tag, commit, dockerfile)
                    : kaniko.build(build.getId(), request, tag, commit, dockerfile, registry, authSecret);
            build.image(image);
            build.log("build: pushed " + image);

            advance(build, Build.Status.DEPLOYING, "deploy: lily-cicd"
                    + (build.getDatabase() == null ? "" : " database=" + build.getDatabase()));
            Instant started = Instant.now();
            CicdClient.Result result;
            try (ProgressWatch ignored = watchProgress(build, targetCicd)) {
                try {
                    result = targetCicd.deploy(request, image, tag, migrations,
                            request.isStandby() ? edge.aliases(request.appName()) : List.of());
                } catch (RestClientResponseException e) {
                    throw e;
                } catch (RestClientException e) {
                    // 응답이 없다는 것은 실패가 아니다. cicd 가 아직 배포 중일 수 있다. POST 는 다시 보내지 않는다
                    build.log("deploy: response lost, following progress");
                    store.save(build);
                    DeployFollow.Outcome outcome = watching.await(build, started);
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
                // 클라우드 버스팅 대기: 이미지·Ingress·DB 는 준비해 두고 Pod 만 0 으로. 공개 주소는 온프레미스 그대로
                if (servesCloud(build, request.appName())) {
                    build.log("standby: public address points to the cloud, replicas kept");
                } else {
                    targetCicd.scale(request.appName(), 0);
                    build.log("standby: scaled to 0");
                }
                edge(build, request.appName(), provider);
            } else {
                address(build, request.appName(), provider);
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
            if (error != null && error.message() != null) {
                // 응답 원문(JSON)을 그대로 보이지 않는다. 로그는 줄로, 이유는 한 줄로
                if (error.logs() != null) {
                    error.logs().forEach(line -> build.log("cicd: " + line));
                }
                failDiagnosed(build, attempt, "cicd: " + error.message());
                return;
            }
            failDiagnosed(build, attempt, "cicd " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            if (build.getStatus() == Build.Status.CANCELLED) {
                // 취소가 Kaniko Job 을 지웠거나 다음 단계로 넘어가지 못하게 했다. 상태는 취소가 이미 남겼다
                log.info("build stopped after cancel: id={} at={}", build.getId(), e.getMessage());
                return;
            }
            failDiagnosed(build, attempt, e.getMessage());
        }
    }

    /** 취소되지 않았으면 다음 단계로 넘어간다 */
    private void advance(Build build, Build.Status status, String line) {
        synchronized (transitions) {
            if (build.getStatus() == Build.Status.CANCELLED) {
                throw new IllegalStateException("cancelled before " + status);
            }
            update(build, status, line);
        }
    }

    /** 실패했을 때 어디까지 정해졌는지 */
    private static final class Attempt {
        BuildRequest request;
        String commit;
        String detectDir;

        Attempt(BuildRequest request) {
            this.request = request;
        }
    }

    /**
     * 원인과 고칠 방법을 정한 뒤에 FAILED 로 바꾼다. 화면(lily-frontend 실행기)은 FAILED 를 보자마자 결과를 가져가므로 순서가 중요하다.
     */
    private void failDiagnosed(Build build, Attempt attempt, String reason) {
        try {
            List<String> logs = new java.util.ArrayList<>(build.getLogs());
            logs.add("failed: " + reason);
            List<ConfigAdvisor.Advice> advices = attempt.commit == null ? List.of()
                    : configAdvice(attempt.request, attempt.commit, attempt.detectDir);
            build.diagnosis(diagnoser.diagnose(logs, new FailureDiagnoser.Context(
                    attempt.request.targetPort(), build.getDatabase(), null), advices));
        } catch (RuntimeException e) {
            log.warn("diagnosis failed: id={} message={}", build.getId(), e.getMessage());
        }
        fail(build, reason);
    }

    static ConfigAdvisor offlineConfig() {
        return new ConfigAdvisor(AiAdvisor.offline());
    }

    static FailureDiagnoser offlineDiagnoser() {
        return new FailureDiagnoser(offlineConfig(), AiAdvisor.offline());
    }

    /**
     * 앱 폴더의 설정 키를 찾고 채울 방법을 정한다. GitHub 를 못 읽으면 빈 목록 (배포 판단을 막지 않는다).
     *
     * @param detectDir rootDir 기준 앱 폴더. null 이면 rootDir
     */
    List<ConfigAdvisor.Advice> configAdvice(BuildRequest request, String commit, String detectDir) {
        try {
            String dir = String.join("/", java.util.stream.Stream.of(request.rootDir(), detectDir)
                    .filter(v -> v != null && !v.isBlank()).map(v -> v.replaceAll("^/+|/+$", "")).toList());
            String prefix = dir.isEmpty() ? "" : dir + "/";
            List<String> paths = github.paths(request, commit).stream()
                    .filter(p -> p.startsWith(prefix))
                    .map(p -> p.substring(prefix.length()))
                    .toList();
            Map<String, String> files = fetchAll(ConfigScanner.wanted(paths),
                    path -> github.file(request, commit, detectDir == null ? path : detectDir + "/" + path));
            return config.advise(ConfigScanner.scan(paths, files::get));
        } catch (RuntimeException e) {
            log.warn("config scan failed: repo={} message={}", request.repoUrl(), e.getMessage());
            return List.of();
        }
    }

    /** raw 파일을 동시에 받는다. 수백 개를 하나씩 받으면 분 단위가 걸린다 */
    private static Map<String, String> fetchAll(List<String> paths, java.util.function.Function<String, String> read) {
        Map<String, String> files = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(16);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (String path : paths) {
                executor.submit(() -> {
                    try {
                        slots.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        String text = read.apply(path);
                        if (text != null) {
                            files.put(path, text);
                        }
                    } catch (RuntimeException e) {
                        log.debug("file skipped: {} {}", path, e.getMessage());
                    } finally {
                        slots.release();
                    }
                });
            }
        }
        return files;
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
        boolean blankRoot = request.rootDir() == null || request.rootDir().isBlank();
        // 레포에 Dockerfile 이 있으면 그대로 믿는다
        if (root.found() && root.generated() != null && blankRoot) {
            String client = clientApp(request, commit);
            if (client != null) {
                // 루트가 모바일·데스크톱 앱(expo, electron)이면 서버로 띄울 수 없다. 하위 폴더의 서버 앱을 본다
                List<Folder> servers = appFolders(request, commit, envKeys).stream()
                        .filter(f -> f.client() == null).toList();
                if (!servers.isEmpty()) {
                    build.log("source: root is " + client + ", looking at sub folders");
                    root = new Folder(request, false, null);
                }
            }
        }
        if (root.found()) {
            if (root.generated() != null) {
                build.log("source: no Dockerfile, generated for " + root.generated().stack());
            }
            return new Source(request, root.dockerfile(), null);
        }
        if (request.rootDir() != null && !request.rootDir().isBlank()) {
            throw new IllegalStateException(NOTHING_TO_BUILD + ". 폴더 " + request.rootDir() + " 를 확인한다");
        }

        List<Folder> apps = appFolders(request, commit, envKeys);
        if (apps.isEmpty()) {
            throw new IllegalStateException(NOTHING_TO_BUILD + ". 루트와 최상위 폴더 모두에 없다");
        }
        List<Folder> servable = apps.stream().filter(f -> f.client() == null).toList();
        if (!servable.isEmpty() && servable.size() < apps.size()) {
            // 데스크톱·모바일 앱은 컨테이너로 띄울 수 없다. 남은 것만 본다
            build.log("source: skipped " + apps.stream().filter(f -> f.client() != null)
                    .map(f -> f.dir() + " (" + f.client() + ")").collect(java.util.stream.Collectors.joining(", ")));
            apps = servable;
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

    /** 레포 최상위 폴더 중 빌드할 수 있는 것 */
    private List<Folder> appFolders(BuildRequest request, String commit, java.util.Set<String> envKeys) {
        List<Folder> apps = new java.util.ArrayList<>();
        for (String dir : github.folders(request, commit)) {
            BuildRequest sub = request.withSource(dir, request.migrationsPath(), request.targetPort());
            Folder folder = folder(sub, commit, envKeys);
            if (folder.found()) {
                apps.add(folder.withClient(clientApp(sub, commit)));
            }
        }
        return apps;
    }

    /** 서버로 띄울 수 없는 앱이면 종류 (electron 등). 아니면 null */
    private String clientApp(BuildRequest request, String commit) {
        String pkg = github.file(request, commit, "package.json");
        if (pkg != null) {
            for (String marker : List.of("electron", "expo", "react-native")) {
                if (pkg.contains("\"" + marker + "\"")) {
                    return marker + " app";
                }
            }
        }
        String gradle = github.file(request, commit, "build.gradle");
        if (gradle == null) {
            gradle = github.file(request, commit, "build.gradle.kts");
        }
        if (gradle != null && gradle.contains("com.android.application")) {
            return "android app";
        }
        return null;
    }

    /**
     * 배포 화면이 보여 줄 앱 폴더 후보.
     *
     * @param client 서버로 띄울 수 없는 앱이면 종류. 아니면 null
     */
    public record AppCandidate(String dir, String stack, String client) {
    }

    /** 폴더 하나를 본 결과. Dockerfile 이 있으면 generated 는 null */
    private record Folder(BuildRequest request, boolean hasDockerfile, DockerfileGenerator.Generated generated,
                          String client) {
        Folder(BuildRequest request, boolean hasDockerfile, DockerfileGenerator.Generated generated) {
            this(request, hasDockerfile, generated, null);
        }

        Folder withClient(String client) {
            return new Folder(request, hasDockerfile, generated, client);
        }

        AppCandidate candidate() {
            return new AppCandidate(dir(), generated == null ? "Dockerfile" : generated.stack(), client);
        }

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
        Build probe = new Build("detect", request);
        List<AppCandidate> apps = List.of();
        if (isBlank(request.rootDir()) && !folder(request, commit, java.util.Set.of()).found()) {
            apps = appFolders(request, commit, java.util.Set.of()).stream().map(Folder::candidate).toList();
        }
        Source source;
        try {
            source = source(probe, request, commit);
        } catch (IllegalStateException e) {
            if (apps.size() > 1) {
                // 폴더를 고르면 다시 감지한다
                return new Detection(null, null, null, apps, List.of(), e.getMessage());
            }
            throw e;
        }
        AppDetector.Result found = detectFiles(source.request(), commit, source.dockerfile(), source.detectDir());
        String dir = source.detectDir() != null ? source.detectDir() : source.request().rootDir();
        List<ConfigAdvisor.Advice> keys = configAdvice(source.request(), commit, source.detectDir());
        // 배포가 폴더를 정할 수 있으면(앱 하나, 백엔드+프론트 묶음, 데스크톱 앱 제외) 고르게 하지 않는다
        return new Detection(found.database(), found.databaseSource(), isBlank(dir) ? null : dir, List.of(), keys, null);
    }

    /**
     * 실제 배포가 볼 앱 폴더 (레포 루트 기준, 루트면 ""). {@link #source} 와 같은 방법으로 정한다.
     * 백엔드와 프론트를 한 이미지로 묶으면 백엔드 폴더다 ({@link #inspect} 의 dir 과 같다). 빌드는 하지 않는다.
     *
     * @throws IllegalStateException 빌드할 앱이 없거나 폴더가 여러 개라 하나로 정하지 못했을 때
     */
    public String appFolder(BuildRequest request, String commit) {
        Source source = source(new Build("analysis", request), request, commit);
        String dir = source.detectDir() != null ? source.detectDir() : source.request().rootDir();
        return isBlank(dir) ? "" : dir;
    }

    /**
     * @param database       postgres / mysql. 드라이버가 안 보이면 null
     * @param databaseSource 근거. 예: {@code build.gradle: org.postgresql}
     * @param dir            본 폴더 (레포 루트 기준). 루트면 null
     * @param apps           레포 루트에 앱이 없을 때 최상위 앱 폴더 후보. 아니면 빈 목록
     * @param config         앱이 기동할 때 읽는 설정 키와 채울 방법
     * @param problem        폴더를 하나로 정하지 못했을 때 이유. 화면이 apps 에서 고르게 한다
     */
    public record Detection(String database, String databaseSource, String dir, List<AppCandidate> apps,
                            List<ConfigAdvisor.Advice> config, String problem) {
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * lily-cicd 배포 요청은 끝날 때까지 응답하지 않는다. 그동안 1초마다 진행 단계를 물어 로그에 남긴다.
     * 이번 배포를 시작한 뒤에 바뀐 단계만 남긴다.
     */
    private ProgressWatch watchProgress(Build build, CicdClient client) {
        Instant since = Instant.now();
        ProgressWatch watch = new ProgressWatch();
        watch.thread = Thread.ofVirtual().name("progress-" + build.getId()).start(() -> {
            String last = null;
            while (!watch.done) {
                try {
                    CicdClient.Progress progress = client.progress(build.getAppName());
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

    /**
     * 공개 주소가 지금 클라우드(ALB)를 가리킨다. 그러면 대기 배포가 끝나도 Pod 를 내리지 않는다 (사용자 요청을 받는 Pod 다).
     * 확인하지 못하면 내리지 않는 쪽을 고른다
     */
    private boolean servesCloud(Build build, String app) {
        if (!addresses.enabled()) {
            return false;
        }
        try {
            return addresses.state(app).home() == AppAddress.Home.CLOUD;
        } catch (RuntimeException e) {
            log.warn("address {} check failed: {}", app, e.getMessage());
            build.log("standby: address check failed (" + e.getMessage() + ")");
            return true;
        }
    }

    /** 대기 배포가 끝난 앱에 엣지 Worker 를 건다. 실패해도 배포는 성공이다 (PC 장애 때 CNAME 전환이 남는다) */
    private void edge(Build build, String app, String provider) {
        if (!edge.enabled()) {
            return;
        }
        try {
            String origin = "GCP".equals(provider) && clouds != null ? clouds.origin() : null;
            build.log(origin == null || origin.isBlank() ? edge.attach(app) : edge.attach(app, origin));
        } catch (RuntimeException e) {
            log.warn("edge {} failed: {}", app, e.getMessage());
            build.log("edge: failed " + e.getMessage());
        }
    }

    /** 공개 주소 CNAME 을 ALB 로 둔다. 실패해도 배포는 성공이다 (Ingress 는 이미 바뀌었다) */
    private void address(Build build, String app, String provider) {
        if (!addresses.enabled()) {
            return;
        }
        try {
            String origin = "GCP".equals(provider) && clouds != null ? clouds.origin() : null;
            AppAddress.State state = origin == null || origin.isBlank()
                    ? addresses.ensureCloud(app) : addresses.ensureCloud(app, origin);
            build.log(switch (state.home()) {
                case CLOUD -> "address: " + state.host() + " -> cloud";
                case ONPREM -> "address: " + state.host() + " points to an on-prem tunnel, left as is";
                default -> "address: " + state.host() + " has another record, left as is";
            });
        } catch (RuntimeException e) {
            log.warn("address {} failed: {}", app, e.getMessage());
            build.log("address: failed " + e.getMessage());
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
