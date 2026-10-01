package com.lily.builder;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeployFollowTest {

    private final CicdClient cicd = mock(CicdClient.class);
    private final MutableClock clock = new MutableClock();

    @Test
    void 끝난_진행_상태면_그_주소로_닫는다() {
        AtomicInteger calls = new AtomicInteger();
        when(cicd.progress("blog")).thenAnswer(invocation -> calls.getAndIncrement() == 0
                ? progress("deployment", "waiting", "img:1")
                : progress("succeeded", "cutover complete. url=https://blog.example", "img:1"));

        DeployFollow.Outcome outcome = follow(Duration.ofMillis(200)).await(build("img:1"), Instant.EPOCH);

        assertThat(outcome.success().targetHostUrl()).isEqualTo("https://blog.example");
        assertThat(outcome.rolledBackReason()).isNull();
    }

    @Test
    void 카나리_실패는_롤백으로_닫는다() {
        when(cicd.progress("blog")).thenReturn(progress("failed", "canary 판정 실패: error 10%", "img:1"));

        DeployFollow.Outcome outcome = follow(Duration.ofMillis(200)).await(build("img:1"), Instant.EPOCH);

        assertThat(outcome.success()).isNull();
        assertThat(outcome.rolledBackReason()).contains("canary 판정 실패");
    }

    @Test
    void 맥박이_멈추면_POST_없이_중단으로_닫는다() {
        when(cicd.progress("blog")).thenReturn(progress("deployment", "waiting", "img:1"));

        assertThatThrownBy(() -> follow(Duration.ofSeconds(5)).await(build("img:1"), Instant.EPOCH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("interrupted at deployment");
    }

    @Test
    void 다른_이미지의_성공은_이_배포의_결과가_아니다() {
        when(cicd.progress("blog")).thenReturn(progress("succeeded", "url=https://other.example", "other:1"));

        assertThatThrownBy(() -> follow(Duration.ofMillis(25)).await(build("img:1"), Instant.EPOCH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no progress");
    }

    private DeployFollow follow(Duration budget) {
        return new DeployFollow(cicd, Duration.ofMillis(10), Duration.ofMillis(30), budget, clock, clock::advance);
    }

    private CicdClient.Progress progress(String stage, String detail, String image) {
        return new CicdClient.Progress(stage, detail, Instant.parse("2026-10-01T00:00:00Z"), image);
    }

    private static Build build(String image) {
        Build build = new Build("id", new BuildRequest(
                "https://github.com/org/repo", null, null, null, "blog", 8080, null, null, null, Map.of()));
        build.image(image);
        return build;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
