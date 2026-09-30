package com.lily.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DynamoDB Local 이 있을 때만 돈다: DYNAMODB_ENDPOINT=http://localhost:8000 */
@EnabledIfEnvironmentVariable(named = "DYNAMODB_ENDPOINT", matches = ".+")
class DynamoBuildStoreTest {

    @Test
    void 저장하고_다시_읽는다() {
        BuilderProperties props = new BuilderProperties("ns", "reg", false, "http://cicd", "kaniko", 10,
                new BuilderProperties.Dynamodb("lily-builds-test", System.getenv("DYNAMODB_ENDPOINT"), "ap-northeast-2", true), "");
        try (DynamoBuildStore store = new DynamoBuildStore(props)) {
            Build build = new Build("t" + System.nanoTime(), new BuildRequest("https://github.com/org/repo", "dev",
                    "ghp_secret", "backend", "blog", 8080, "postgres", null, null, Map.of()));
            build.status(Build.Status.SUCCEEDED, "done");
            build.image("reg/blog:t");
            build.url("http://blog.domain.com");
            store.save(build);

            Build read = store.find(build.getId()).orElseThrow();
            assertThat(read.getAppName()).isEqualTo("blog");
            assertThat(read.getBranch()).isEqualTo("dev");
            assertThat(read.getRootDir()).isEqualTo("backend");
            assertThat(read.getDatabase()).isEqualTo("postgres");
            assertThat(read.getStatus()).isEqualTo(Build.Status.SUCCEEDED);
            assertThat(read.getImage()).isEqualTo("reg/blog:t");
            assertThat(read.getUrl()).isEqualTo("http://blog.domain.com");
            assertThat(read.getLogs()).containsExactly("done");
            assertThat(store.findAll()).extracting(Build::getId).contains(build.getId());
        }
    }
}
