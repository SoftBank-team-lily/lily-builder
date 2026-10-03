package com.lily.builder.cloud;

import com.lily.builder.GitHubSource;
import com.lily.jev.Answer;
import com.lily.jev.HttpJev;
import com.lily.jev.Jev;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 명시적으로 켰을 때만 실제 저장소와 JEV를 호출한다. 클라우드 리소스는 생성하지 않는다. */
@EnabledIfEnvironmentVariable(named="RUN_JEV_LIVE", matches="true")
class CloudLiveTest {
    /** 실제 JEV 답을 받아 두고 그대로 돌려준다 */
    static Jev recording(AtomicReference<Answer> received) {
        String key = System.getenv("JEV_API_KEY");
        assertThat(key != null && !key.isBlank()).as("JEV key configured").isTrue();
        var client = new HttpJev(key, 0);
        return (state, question) -> {
            Optional<Answer> answer = client.ask(state, question);
            answer.ifPresent(received::set);
            System.out.println(question.id() + " JEV answer: " + answer);
            return answer;
        };
    }

    @Test void readsPublicTeamRepositoryWithoutAskingForGenericDependencies() {
        var request = new com.lily.builder.BuildRequest("https://github.com/SoftBank-team-lily/lily-blog-sample",
            "main",null,null,"cloud-analysis-test",null,null,null,null,null);
        var calls = new AtomicInteger();
        var evidence = new CloudRepository(new GitHubSource(), (s,q) -> { calls.incrementAndGet(); return Optional.empty(); })
            .inspect(request);
        assertThat(evidence.files()).isNotEmpty();
        assertThat(evidence.signals()).isNotEmpty();
        // Spring Boot·PostgreSQL 만 있는 앱은 특정 클라우드 단서가 없다
        assertThat(evidence.affinity()).isEqualTo("portable");
        assertThat(evidence.source()).isEqualTo("rules");
        assertThat(calls.get()).isZero();
        System.out.println("Repository assessment: commit=" + evidence.commit() + ", files=" + evidence.files()
            + ", signals=" + evidence.signals() + ", affinity=" + evidence.affinity());
    }

    @Test void assessesProviderSpecificDependenciesWithRealJev() {
        var github = mock(GitHubSource.class);
        when(github.resolveCommit(any())).thenReturn("a".repeat(40));
        when(github.paths(any(),any())).thenReturn(List.of("requirements.txt"));
        when(github.analysisFile(any(),any(),any())).thenReturn("fastapi\ngoogle-cloud-bigquery\ngoogle-cloud-storage\n");
        var request = new com.lily.builder.BuildRequest("https://github.com/owner/synthetic",
            "main",null,null,"cloud-analysis-test",null,null,null,null,null);
        var received = new AtomicReference<Answer>();
        var evidence = new CloudRepository(github, recording(received)).inspect(request);
        assertThat(received.get()).as("Must receive an actual live JEV response, not hide a network failure").isNotNull();
        if (received.get().confidence() >= CloudPolicy.DEFAULT_MIN_CONFIDENCE) {
            assertThat(evidence.source()).isEqualTo("jev");
            assertThat(evidence.affinity()).isEqualTo(received.get().choice());
        } else {
            assertThat(evidence.source()).isEqualTo("rules");
            assertThat(evidence.affinity()).isEqualTo("unknown");
        }
    }

    @Test void choosesBetweenTradeOffCandidatesWithRealJev() {
        Instant now = Instant.now();
        var request = new CloudPolicy.Request("auto","balanced","synthetic-test",new BigDecimal("100"),200,
            Set.of("ap-northeast-2","asia-northeast3"),Set.of("container"));
        // aws 는 싸고 느리고 gcp 는 비싸고 빠르다
        var aws = new CloudPolicy.Candidate("aws","ap-northeast-2","synthetic-test",new BigDecimal("20"),150.,true,Set.of("container"),now,"synthetic-aws");
        var gcp = new CloudPolicy.Candidate("gcp","asia-northeast3","synthetic-test",new BigDecimal("70"),50.,true,Set.of("container"),now,"synthetic-gcp");
        var received = new AtomicReference<Answer>();
        var result = new CloudPolicy(recording(received)).decide(request,List.of(aws,gcp),CloudPolicyTest.WORKERS,now,300);
        assertThat(received.get()).as("Must receive an actual live JEV response, not hide a network failure").isNotNull();
        if (received.get().confidence() >= CloudPolicy.DEFAULT_MIN_CONFIDENCE) {
            assertThat(result.source()).isEqualTo("jev");
            assertThat(received.get().choice().equals("hold") ? result.status() : result.provider())
                .isEqualTo(received.get().choice().equals("hold") ? "held" : received.get().choice());
        } else {
            assertThat(result.reason()).isEqualTo("jev_fallback");
        }
    }
}
