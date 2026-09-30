package com.lily.builder;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * k3s 에 Kaniko Job 을 띄워 이미지를 빌드하고 레지스트리에 올린다.
 * Kaniko 가 GitHub 에서 직접 clone 하므로 이 서버는 소스를 받지 않는다.
 */
@Component
public class KanikoBuilder {

    /**
     * ECR push 인증. 클러스터의 ecr-credentials CronJob 이 lily-builds namespace 에 6시간마다 갱신하는
     * docker 인증 Secret(kubernetes.io/dockerconfigjson)을 그대로 마운트한다.
     * 노드 IAM 역할(IMDS)을 쓰지 않으므로 빌드(= 사용자 Dockerfile 의 RUN)는 AWS 권한이 없는 worker 에서 돈다.
     */
    static final String REGISTRY_AUTH_SECRET = "ecr-pull";

    private final KubernetesClient k8s;
    private final BuilderProperties props;
    private final long pollMillis;

    @Autowired
    public KanikoBuilder(KubernetesClient k8s, BuilderProperties props) {
        this(k8s, props, 5000);
    }

    KanikoBuilder(KubernetesClient k8s, BuilderProperties props, long pollMillis) {
        this.k8s = k8s;
        this.props = props;
        this.pollMillis = pollMillis;
    }

    /**
     * 빌드가 끝나면 이미지 주소를 돌려준다. 실패하면 Kaniko 로그 끝부분을 담아 예외를 던진다.
     *
     * @param commit 빌드할 커밋 SHA. null 이면 브랜치 끝
     */
    public String build(String buildId, BuildRequest request, String tag, String commit) {
        String image = props.registry() + "/" + request.appName() + ":" + tag;
        String jobName = "build-" + buildId;
        String ns = props.namespace();
        boolean hasToken = request.token() != null && !request.token().isBlank();
        try {
            if (hasToken) {
                k8s.secrets().inNamespace(ns).resource(new SecretBuilder()
                        .withNewMetadata().withName(jobName).endMetadata()
                        .addToStringData("GIT_USERNAME", "x-access-token")
                        .addToStringData("GIT_PASSWORD", request.token())
                        .build()).create();
            }
            k8s.batch().v1().jobs().inNamespace(ns).resource(job(jobName, request, image, hasToken, commit)).create();
            Job done = awaitFinished(ns, jobName);
            if (!succeeded(done)) {
                throw new IllegalStateException("kaniko build failed\n" + tail(ns, jobName));
            }
            return image;
        } catch (KubernetesClientException e) {
            throw new IllegalStateException("kaniko build error: " + e.getMessage() + "\n" + tail(ns, jobName), e);
        } finally {
            if (hasToken) {
                k8s.secrets().inNamespace(ns).withName(jobName).delete();
            }
        }
    }

    Job job(String name, BuildRequest request, String image, boolean hasToken, String commit) {
        List<String> args = new ArrayList<>(List.of(
                "--context=" + request.gitContext(commit),
                "--dockerfile=Dockerfile",
                "--destination=" + image));
        if (request.rootDir() != null && !request.rootDir().isBlank()) {
            args.add("--context-sub-path=" + request.rootDir().replaceAll("^/+|/+$", ""));
        }
        if (props.insecure()) {
            args.add("--insecure");
            args.add("--skip-tls-verify");
        }
        List<EnvVar> env = hasToken
                ? List.of(secretEnv(name, "GIT_USERNAME"), secretEnv(name, "GIT_PASSWORD"))
                : List.of();

        JobBuilder builder = new JobBuilder()
                .withNewMetadata().withName(name).addToLabels("app", "lily-build").endMetadata()
                .withNewSpec()
                    .withBackoffLimit(0)
                    .withTtlSecondsAfterFinished(3600)
                    .withActiveDeadlineSeconds(props.buildTimeoutSeconds())
                    .withNewTemplate()
                        .withNewSpec()
                            .withRestartPolicy("Never")
                            .addNewContainer()
                                .withName("kaniko")
                                .withImage(props.kanikoImage())
                                .withArgs(args)
                                .withEnv(env)
                            .endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec();
        if (props.ecr()) {
            // 사용자 Dockerfile 이 실행되므로 AWS 권한이 있는 lily-server 에 두지 않는다 (worker 는 IMDS 차단).
            // worker 는 사용자 앱과 같은 노드라 메모리 상한을 둔다
            builder.editSpec().editTemplate().editSpec()
                    .addNewVolume().withName("docker-config")
                        .withNewSecret().withSecretName(REGISTRY_AUTH_SECRET)
                            .addNewItem().withKey(".dockerconfigjson").withPath("config.json").endItem()
                        .endSecret()
                    .endVolume()
                    .editFirstContainer()
                        .addNewVolumeMount().withName("docker-config").withMountPath("/kaniko/.docker").withReadOnly(true).endVolumeMount()
                        .withNewResources()
                            .addToRequests("cpu", new Quantity("500m"))
                            .addToRequests("memory", new Quantity("512Mi"))
                            .addToLimits("memory", new Quantity("2Gi"))
                        .endResources()
                    .endContainer()
                    .endSpec().endTemplate().endSpec();
        }
        return builder.build();
    }

    private static EnvVar secretEnv(String secret, String key) {
        return new EnvVarBuilder().withName(key)
                .withNewValueFrom().withNewSecretKeyRef(key, secret, false).endValueFrom()
                .build();
    }

    /**
     * 몇 초마다 Job 을 조회한다.
     * watch(waitUntilCondition) 는 Job 이 도중에 지워지면 재연결을 끝없이 반복해서 CPU 를 다 쓴다.
     */
    private Job awaitFinished(String ns, String jobName) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(props.buildTimeoutSeconds());
        while (System.nanoTime() < deadline) {
            Job job = k8s.batch().v1().jobs().inNamespace(ns).withName(jobName).get();
            if (job == null) {
                throw new IllegalStateException("kaniko job disappeared: " + jobName);
            }
            if (finished(job)) {
                return job;
            }
            sleep(pollMillis);
        }
        throw new IllegalStateException("kaniko build timed out after " + props.buildTimeoutSeconds() + "s");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for kaniko job", e);
        }
    }

    private static boolean finished(Job job) {
        if (job.getStatus() == null) {
            return false;
        }
        JobStatus s = job.getStatus();
        return (s.getSucceeded() != null && s.getSucceeded() > 0) || (s.getFailed() != null && s.getFailed() > 0);
    }

    private static boolean succeeded(Job job) {
        return job.getStatus().getSucceeded() != null && job.getStatus().getSucceeded() > 0;
    }

    private String tail(String ns, String jobName) {
        try {
            String log = k8s.batch().v1().jobs().inNamespace(ns).withName(jobName).getLog();
            List<String> lines = Arrays.asList(log.split("\n"));
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 20), lines.size()));
        } catch (RuntimeException e) {
            return "(log unavailable: " + e.getMessage() + ")";
        }
    }
}
