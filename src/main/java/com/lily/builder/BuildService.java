package com.lily.builder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
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
    private static final DateTimeFormatter TAG = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    private final BuildStore store;
    private final KanikoBuilder kaniko;
    private final CicdClient cicd;
    private final BuildRunner runner;
    private final EcrRepositories ecr;
    private final GitHubSource github;

    public BuildService(BuildStore store, KanikoBuilder kaniko, CicdClient cicd, BuildRunner runner,
                        EcrRepositories ecr, GitHubSource github) {
        this.store = store;
        this.kaniko = kaniko;
        this.cicd = cicd;
        this.runner = runner;
        this.ecr = ecr;
        this.github = github;
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

    void execute(Build build, BuildRequest request) {
        String tag = ZonedDateTime.now(ZoneOffset.UTC).format(TAG);
        try {
            String commit = github.resolveCommit(request);
            build.log("source: commit " + commit);
            Map<String, String> migrations = github.migrations(request, commit);
            build.log(migrations.isEmpty()
                    ? "source: migrations none (" + (request.migrateOrDefault() ? GitHubSource.folder(request) : "migrate=false") + ")"
                    : "source: migrations " + migrations.keySet());
            if (ecr.ensure(request.appName())) {
                build.log("build: created ecr repository " + request.appName());
            }
            update(build, Build.Status.BUILDING, "build: kaniko job build-" + build.getId());
            String image = kaniko.build(build.getId(), request, tag, commit);
            build.image(image);
            build.log("build: pushed " + image);

            update(build, Build.Status.DEPLOYING, "deploy: lily-cicd"
                    + (build.getDatabase() == null ? "" : " database=" + build.getDatabase()));
            CicdClient.Result result;
            try (ProgressWatch ignored = watchProgress(build)) {
                result = cicd.deploy(request, image, tag, migrations);
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
