package com.lily.builder;

import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 배포 POST 의 응답이 없어도 진행 상태를 끝까지 본다. POST 는 다시 보내지 않는다.
 *
 * <p>lily-cicd 가 살아 있으면 단계 시각이 몇 초마다 앞으로 간다. 시각이 멈추면 그 프로세스는 죽은 것이다.
 * 끝난 단계({@code succeeded}, {@code failed})가 보이면 그 결과로 빌드를 닫는다.
 */
final class DeployFollow {

    static final Duration POLL = Duration.ofSeconds(1);
    /** 진행 맥박(5초)이 여러 번 빠질 때까지 둔다. Ready 대기는 맥박이 있어서 여기 걸리지 않는다 */
    static final Duration STALE = Duration.ofSeconds(30);
    /** lily-cicd 요청 제한(400초)과 builder 읽기 제한(420초)과 같다 */
    static final Duration BUDGET = Duration.ofSeconds(420);

    private final CicdClient cicd;
    private final Duration poll;
    private final Duration stale;
    private final Duration budget;
    private final Clock clock;
    private final Sleeper sleeper;

    DeployFollow(CicdClient cicd) {
        this(cicd, POLL, STALE, BUDGET, Clock.systemUTC(), duration -> Thread.sleep(duration.toMillis()));
    }

    DeployFollow(CicdClient cicd, Duration poll, Duration stale, Duration budget, Clock clock, Sleeper sleeper) {
        this.cicd = cicd;
        this.poll = poll;
        this.stale = stale;
        this.budget = budget;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * @param started 이 빌드가 배포를 시작한 시각. 이미지 없는 옛 진행 기록은 이 시각 이후만 본다
     */
    Outcome await(Build build, Instant started) {
        Instant deadline = clock.instant().plus(budget);
        Instant quietSince = null;
        String seen = null;
        while (clock.instant().isBefore(deadline)) {
            CicdClient.Progress progress = poll(build.getAppName());
            if (ours(build, progress, started)) {
                String mark = progress.stage() + "|" + progress.updatedAt();
                if (!mark.equals(seen)) {
                    seen = mark;
                    quietSince = clock.instant();
                } else if (quietSince != null && !clock.instant().isBefore(quietSince.plus(stale))) {
                    throw new IllegalStateException(
                            "deploy interrupted at " + progress.stage() + ": lily-cicd progress stopped");
                }
                if ("succeeded".equals(progress.stage())) {
                    return Outcome.ok(new CicdClient.Result("SUCCESS", null, urlOf(progress.detail()), null,
                            progress.detail() == null ? java.util.List.of() : java.util.List.of(progress.detail())));
                }
                if ("failed".equals(progress.stage())) {
                    String detail = progress.detail() == null ? "deploy failed" : progress.detail();
                    if (detail.contains("canary 판정 실패")) {
                        return Outcome.rolledBack(detail);
                    }
                    throw new IllegalStateException(detail);
                }
            }
            try {
                sleeper.sleep(poll);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("deploy follow interrupted");
            }
        }
        throw new IllegalStateException(
                "deploy response lost and lily-cicd reported no progress for " + build.getImage());
    }

    private CicdClient.Progress poll(String appName) {
        try {
            return cicd.progress(appName);
        } catch (RestClientException e) {
            return null;
        }
    }

    private static boolean ours(Build build, CicdClient.Progress progress, Instant started) {
        if (progress == null || progress.stage() == null) {
            return false;
        }
        if (build.getImage() != null && progress.image() != null) {
            return build.getImage().equals(progress.image());
        }
        if (progress.image() != null) {
            return false;
        }
        return progress.updatedAt() != null && started != null && !progress.updatedAt().isBefore(started.minusSeconds(5));
    }

    private static String urlOf(String detail) {
        if (detail == null) {
            return null;
        }
        int at = detail.indexOf(" url=");
        if (at < 0) {
            return null;
        }
        String rest = detail.substring(at + " url=".length()).trim();
        int space = rest.indexOf(' ');
        return space < 0 ? rest : rest.substring(0, space);
    }

    /** @param success 배포가 끝남. 롤백이면 null @param rolledBackReason canary 로 되돌린 이유. 성공이면 null */
    record Outcome(CicdClient.Result success, String rolledBackReason) {
        static Outcome ok(CicdClient.Result success) {
            return new Outcome(success, null);
        }

        static Outcome rolledBack(String reason) {
            return new Outcome(null, reason);
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
