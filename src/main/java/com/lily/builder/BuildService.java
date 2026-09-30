package com.lily.builder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 폼 입력 → Kaniko 빌드 → lily-cicd 배포.
 * 단계가 바뀔 때마다 배포 이력 저장소에 기록한다. 화면은 저장소를 조회한다.
 */
@Service
public class BuildService {

    private static final Logger log = LoggerFactory.getLogger(BuildService.class);
    private static final DateTimeFormatter TAG = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BuildStore store;
    private final KanikoBuilder kaniko;
    private final CicdClient cicd;
    private final BuildRunner runner;

    public BuildService(BuildStore store, KanikoBuilder kaniko, CicdClient cicd, BuildRunner runner) {
        this.store = store;
        this.kaniko = kaniko;
        this.cicd = cicd;
        this.runner = runner;
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
            update(build, Build.Status.BUILDING, "build: kaniko job build-" + build.getId());
            String image = kaniko.build(build.getId(), request, tag);
            build.image(image);
            build.log("build: pushed " + image);

            update(build, Build.Status.DEPLOYING, "deploy: lily-cicd"
                    + (build.getDatabase() == null ? "" : " database=" + build.getDatabase()));
            CicdClient.Result result = cicd.deploy(request, image, tag);
            if (result != null && result.logs() != null) {
                result.logs().forEach(line -> build.log("cicd: " + line));
            }
            build.url(result == null ? null : result.targetHostUrl());
            update(build, Build.Status.SUCCEEDED, "done: " + build.getUrl());
        } catch (RestClientResponseException e) {
            fail(build, "cicd " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            fail(build, e.getMessage());
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
