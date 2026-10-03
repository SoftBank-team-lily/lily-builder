package com.lily.builder.cloud;

import com.lily.jev.HttpJev;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

/** 명시적으로 켰을 때만 합성 관측값으로 JEV를 호출한다. 클라우드 리소스는 생성하지 않는다. */
@EnabledIfEnvironmentVariable(named="RUN_JEV_LIVE", matches="true")
class CloudLiveTest {
    @Test void readsPublicTeamRepositoryAndAssessesItWithRealJev() {
        var request = new com.lily.builder.BuildRequest("https://github.com/SoftBank-team-lily/lily-blog-sample",
            "main",null,null,"cloud-analysis-test",null,null,null,null,null);
        var client = new HttpJev(System.getenv("JEV_API_KEY"),0);
        var received = new java.util.concurrent.atomic.AtomicReference<com.lily.jev.Answer>();
        var repository = new CloudRepository(new com.lily.builder.GitHubSource(), (state,question) -> {
            var answer = client.ask(state,question);
            answer.ifPresent(received::set);
            System.out.println("Repository JEV answer: " + answer);
            return answer;
        });
        var evidence = repository.inspect(request);
        assertThat(evidence.files()).isNotEmpty();
        assertThat(evidence.signals()).isNotEmpty();
        assertThat(received.get()).as("Must receive an actual live JEV response, not hide a network failure").isNotNull();
        if (received.get().confidence() >= .8) {
            assertThat(evidence.source()).isEqualTo("jev");
            assertThat(evidence.affinity()).isEqualTo(received.get().choice());
        } else {
            assertThat(evidence.source()).isEqualTo("rules");
            assertThat(evidence.affinity()).isEqualTo("unknown");
        }
        System.out.println("Repository assessment: commit=" + evidence.commit() + ", files=" + evidence.files()
            + ", signals=" + evidence.signals() + ", affinity=" + evidence.affinity() + ", confidence=" + evidence.confidence());
    }
    @Test void selectsCheaperAndFasterAwsWithRealJev() {
        String key = System.getenv("JEV_API_KEY");
        assertThat(key != null && !key.isBlank()).as("JEV key configured").isTrue();
        Instant now = Instant.now();
        var request = new CloudPolicy.Request("auto","cost","synthetic-test",new BigDecimal("100"),200,
            Set.of("ap-northeast-2","asia-northeast3"),Set.of("container"));
        var aws = new CloudPolicy.Candidate("aws","ap-northeast-2","synthetic-test",new BigDecimal("20"),50.,true,Set.of("container"),now,"synthetic-aws");
        var gcp = new CloudPolicy.Candidate("gcp","asia-northeast3","synthetic-test",new BigDecimal("70"),150.,true,Set.of("container"),now,"synthetic-gcp");
        var result = new CloudPolicy(new HttpJev(key,.8)).decide(request,List.of(aws,gcp),CloudPolicyTest.WORKERS,now,300);
        assertThat(result.source()).as("Real JEV must answer; a rules fallback is not a live-model pass").isEqualTo("jev");
        assertThat(result.provider()).isEqualTo("aws");
        assertThat(result.confidence()).isBetween(.8,1.);
    }
}
