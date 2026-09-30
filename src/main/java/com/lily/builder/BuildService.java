package com.lily.builder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 폼 입력 → Kaniko 빌드 → lily-cicd 배포.
 * 진행 상태는 메모리에만 둔다 (초안. 재시작하면 기록이 사라진다).
 */
@Service
public class BuildService {

    private static final Logger log = LoggerFactory.getLogger(BuildService.class);
    private static final DateTimeFormatter TAG = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Map<String, Build> builds = new ConcurrentHashMap<>();
    private final KanikoBuilder kaniko;
    private final CicdClient cicd;
    private final BuildRunner runner;

    public BuildService(KanikoBuilder kaniko, CicdClient cicd, BuildRunner runner) {
        this.kaniko = kaniko;
        this.cicd = cicd;
        this.runner = runner;
    }

    public Build start(BuildRequest request) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Build build = new Build(id, request.appName());
        builds.put(id, build);
        build.log("queued: " + request.repoUrl() + " branch=" + request.branchOrDefault()
                + (request.rootDir() == null || request.rootDir().isBlank() ? "" : " dir=" + request.rootDir()));
        runner.run(() -> execute(build, request));
        return build;
    }

    public Optional<Build> get(String id) {
        return Optional.ofNullable(builds.get(id));
    }

    void execute(Build build, BuildRequest request) {
        String tag = ZonedDateTime.now(ZoneOffset.UTC).format(TAG);
        try {
            build.status(Build.Status.BUILDING, "build: kaniko job build-" + build.getId());
            String image = kaniko.build(build.getId(), request, tag);
            build.image(image);
            build.log("build: pushed " + image);

            build.status(Build.Status.DEPLOYING, "deploy: lily-cicd"
                    + (request.database() == null || request.database().isBlank() ? "" : " database=" + request.database()));
            CicdClient.Result result = cicd.deploy(request, image, tag);
            if (result != null && result.logs() != null) {
                result.logs().forEach(line -> build.log("cicd: " + line));
            }
            build.url(result == null ? null : result.targetHostUrl());
            build.status(Build.Status.SUCCEEDED, "done: " + build.getUrl());
        } catch (RestClientResponseException e) {
            fail(build, "cicd " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            fail(build, e.getMessage());
        }
    }

    private void fail(Build build, String reason) {
        log.warn("build failed: id={} app={} reason={}", build.getId(), build.getAppName(), reason);
        build.status(Build.Status.FAILED, "failed: " + reason);
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
