package com.lily.builder;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnableKubernetesMockClient(crud = true)
class KanikoBuilderTest {

    KubernetesClient client;

    private static final String NS = "lily-builds";

    private KanikoBuilder builder(String registry, boolean insecure) {
        return new KanikoBuilder(client, new BuilderProperties(NS, registry, insecure, "http://cicd", "kaniko:test", 10,
                new BuilderProperties.Dynamodb("t", null, "ap-northeast-2", false)), 50);
    }

    private static BuildRequest request(String rootDir, String token) {
        return new BuildRequest("https://github.com/org/repo", "dev", token, rootDir, "blog", 8080,
                "", null, null, Map.of());
    }

    @Test
    void git_컨텍스트_변환() {
        assertThat(request(null, null).gitContext()).isEqualTo("git://github.com/org/repo.git#refs/heads/dev");
        assertThat(new BuildRequest("https://github.com/org/repo.git/", "", null, null, "a", 80, "", null, null, null)
                .gitContext()).isEqualTo("git://github.com/org/repo.git#refs/heads/main");
    }

    @Test
    void job_인자() {
        Job job = builder("reg:5000", true).job("build-1", request("/backend/", null), "reg:5000/blog:t", false);
        List<String> args = job.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs();

        assertThat(args).contains(
                "--context=git://github.com/org/repo.git#refs/heads/dev",
                "--dockerfile=Dockerfile",
                "--destination=reg:5000/blog:t",
                "--context-sub-path=backend",
                "--insecure", "--skip-tls-verify");
        assertThat(job.getSpec().getBackoffLimit()).isZero();
    }

    @Test
    void ECR_이면_ecr_login_설정을_마운트한다() {
        Job job = builder("123.dkr.ecr.ap-northeast-2.amazonaws.com", false)
                .job("build-1", request(null, null), "img", false);

        assertThat(job.getSpec().getTemplate().getSpec().getVolumes().get(0).getConfigMap().getName())
                .isEqualTo(KanikoBuilder.DOCKER_CONFIG);
        assertThat(job.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs())
                .doesNotContain("--insecure");
    }

    @Test
    void 토큰은_Secret_으로만_넘긴다() {
        Job job = builder("reg", false).job("build-1", request(null, "ghp_secret"), "img", true);
        var env = job.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();

        assertThat(env).extracting(e -> e.getValueFrom().getSecretKeyRef().getName()).containsOnly("build-1");
        assertThat(job.toString()).doesNotContain("ghp_secret");
    }

    @Test
    void 빌드_성공하면_이미지를_돌려주고_토큰_Secret_을_지운다() throws Exception {
        CompletableFuture<String> result = CompletableFuture.supplyAsync(
                () -> builder("reg", false).build("1", request(null, "ghp_secret"), "t"));
        markJob("build-1", true);

        assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo("reg/blog:t");
        assertThat(client.secrets().inNamespace(NS).withName("build-1").get()).isNull();
    }

    @Test
    void 빌드_실패하면_예외() {
        CompletableFuture<String> result = CompletableFuture.supplyAsync(
                () -> builder("reg", false).build("2", request(null, null), "t"));
        markJob("build-2", false);

        assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS)).hasMessageContaining("kaniko build failed");
    }

    @Test
    void 빌드_중_Job_이_지워지면_바로_실패한다() {
        CompletableFuture<String> result = CompletableFuture.supplyAsync(
                () -> builder("reg", false).build("3", request(null, null), "t"));
        awaitJob("build-3");
        client.batch().v1().jobs().inNamespace(NS).withName("build-3").delete();

        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasMessageContaining("disappeared");
    }

    private void awaitJob(String name) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (client.batch().v1().jobs().inNamespace(NS).withName(name).get() == null
                && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
    }

    /** mock 에는 Job 컨트롤러가 없어서 상태를 직접 채운다 */
    private void markJob(String name, boolean succeeded) {
        awaitJob(name);
        var jobs = client.batch().v1().jobs().inNamespace(NS);
        Job job = jobs.withName(name).get();
        job.setStatus(succeeded
                ? new JobStatusBuilder().withSucceeded(1).build()
                : new JobStatusBuilder().withFailed(1).build());
        jobs.resource(job).updateStatus();
    }
}
