package com.lily.builder;

import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 앱을 다른 클라우드로 옮길 때 PostgreSQL DB 를 통째로 복사한다 ({@link AppMigration}).
 * builder 클러스터(AWS k3s)에 Job 을 띄워 {@code pg_dump | psql} 한다. RDS 는 같은 VPC 라 바로 닿고,
 * Cloud SQL 은 사설 IP 라 GCP 배스천으로 {@code ssh -L} 을 연 뒤 그 로컬 포트로 붙는다.
 *
 * <p>대상 DB 는 비어 있어야 한다 (provisioner 가 방금 만든 DB). 소유자·권한은 빼고 복사해 대상 앱 계정이 갖는다.
 */
@Component
public class DatabaseCopy {

    /** 터널 쪽 URL 은 Job 안의 이 로컬 포트를 가리켜야 한다 */
    static final int LOCAL_PORT = 15432;
    static final String IMAGE = "postgres:16-alpine";

    private final KubernetesClient k8s;
    private final BuilderProperties props;
    private final long pollMillis;
    private final long timeoutSeconds;

    @Autowired
    public DatabaseCopy(KubernetesClient k8s, BuilderProperties props) {
        this(k8s, props, 3000, 900);
    }

    DatabaseCopy(KubernetesClient k8s, BuilderProperties props, long pollMillis, long timeoutSeconds) {
        this.k8s = k8s;
        this.props = props;
        this.pollMillis = pollMillis;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 배스천 터널. 원본이나 대상 중 하나가 사설 DB 일 때.
     *
     * @param remote 배스천에서 본 DB 주소 {@code host:port}
     */
    public record Tunnel(String sshHost, String sshUser, String remote, TunnelCertificates.JobKey key) {
    }

    /**
     * 복사가 끝날 때까지 기다린다. Job 과 Secret 은 끝나면 지운다.
     *
     * @param tunnel null 이면 터널 없이 두 URL 로 바로 붙는다
     * @return Job 로그 끝부분 (복사한 크기, 테이블 수)
     * @throws IllegalStateException 복사 실패. 메시지에 Job 로그 끝부분
     */
    public String copy(String name, String sourceUrl, String targetUrl, Tunnel tunnel) {
        String ns = props.namespace();
        String job = "dbcopy-" + name;
        SecretBuilder secret = new SecretBuilder()
                .withNewMetadata().withName(job).addToLabels("app", "lily-dbcopy").endMetadata()
                .addToStringData("SOURCE_URL", sourceUrl)
                .addToStringData("TARGET_URL", targetUrl);
        if (tunnel != null) {
            secret.addToStringData("SSH_KEY", tunnel.key().privateKey())
                    .addToStringData("SSH_CERT", tunnel.key().certificate());
        }
        try {
            k8s.secrets().inNamespace(ns).resource(secret.build()).create();
            k8s.batch().v1().jobs().inNamespace(ns).resource(job(job, tunnel)).create();
            Job done = awaitFinished(ns, job);
            String tail = tail(ns, job);
            if (!succeeded(done)) {
                throw new IllegalStateException("DB 복사 실패\n" + tail);
            }
            return tail;
        } finally {
            k8s.batch().v1().jobs().inNamespace(ns).withName(job)
                    .withPropagationPolicy(DeletionPropagation.BACKGROUND).delete();
            k8s.secrets().inNamespace(ns).withName(job).delete();
        }
    }

    Job job(String name, Tunnel tunnel) {
        List<EnvVar> env = new ArrayList<>(List.of(secretEnv(name, "SOURCE_URL"), secretEnv(name, "TARGET_URL")));
        if (tunnel != null) {
            env.add(secretEnv(name, "SSH_KEY"));
            env.add(secretEnv(name, "SSH_CERT"));
            env.add(new EnvVarBuilder().withName("TUNNEL_HOST").withValue(tunnel.sshHost()).build());
            env.add(new EnvVarBuilder().withName("TUNNEL_USER").withValue(tunnel.sshUser()).build());
            env.add(new EnvVarBuilder().withName("TUNNEL_REMOTE").withValue(tunnel.remote()).build());
        }
        return new JobBuilder()
                .withNewMetadata().withName(name).addToLabels("app", "lily-dbcopy").endMetadata()
                .withNewSpec()
                    .withBackoffLimit(0)
                    .withTtlSecondsAfterFinished(600)
                    .withActiveDeadlineSeconds(timeoutSeconds)
                    .withNewTemplate()
                        .withNewMetadata().addToLabels("app", "lily-dbcopy").endMetadata()
                        .withNewSpec()
                            .withRestartPolicy("Never")
                            .addNewContainer()
                                .withName("copy")
                                .withImage(IMAGE)
                                .withCommand("sh", "-c", SCRIPT)
                                .withEnv(env)
                                .withNewResources()
                                    .addToRequests("cpu", new Quantity("100m"))
                                    .addToRequests("memory", new Quantity("128Mi"))
                                    .addToLimits("memory", new Quantity("512Mi"))
                                .endResources()
                            .endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }

    /**
     * 터널이 있으면 openssh 를 받아 -L 을 열고 로컬 포트가 열릴 때까지 기다린다. 덤프는 파일로 받은 뒤 넣는다
     * (덤프가 실패했는데 절반만 넣는 일을 막는다). 대상에서 처음 오류가 나면 멈춘다.
     */
    static final String SCRIPT = """
            set -eu
            if [ -n "${TUNNEL_HOST:-}" ]; then
              apk add --no-cache openssh-client >/dev/null
              mkdir -p /tmp/ssh
              printf '%%s\\n' "$SSH_KEY" > /tmp/ssh/id
              printf '%%s\\n' "$SSH_CERT" > /tmp/ssh/id-cert.pub
              chmod 600 /tmp/ssh/id
              ssh -N -o ExitOnForwardFailure=yes -o StrictHostKeyChecking=accept-new \\
                -o UserKnownHostsFile=/tmp/ssh/known_hosts -o BatchMode=yes -o ServerAliveInterval=10 \\
                -i /tmp/ssh/id -o CertificateFile=/tmp/ssh/id-cert.pub \\
                -L 127.0.0.1:%d:"$TUNNEL_REMOTE" "$TUNNEL_USER@$TUNNEL_HOST" &
              i=0
              until nc -z 127.0.0.1 %d; do
                i=$((i+1)); [ "$i" -gt 60 ] && { echo "copy: tunnel not ready"; exit 1; }
                sleep 0.5
              done
              echo "copy: tunnel ready"
            fi
            pg_dump --no-owner --no-privileges --format=plain "$SOURCE_URL" > /tmp/dump.sql
            echo "copy: dumped $(wc -c < /tmp/dump.sql) bytes"
            psql -v ON_ERROR_STOP=1 -q "$TARGET_URL" < /tmp/dump.sql > /dev/null
            echo "copy: restored, tables $(psql -Atc "select count(*) from information_schema.tables where table_schema not in ('pg_catalog','information_schema')" "$TARGET_URL")"
            """.formatted(LOCAL_PORT, LOCAL_PORT);

    private static EnvVar secretEnv(String secret, String key) {
        return new EnvVarBuilder().withName(key)
                .withNewValueFrom().withNewSecretKeyRef(key, secret, false).endValueFrom()
                .build();
    }

    private Job awaitFinished(String ns, String name) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds + 30);
        while (System.nanoTime() < deadline) {
            Job job = k8s.batch().v1().jobs().inNamespace(ns).withName(name).get();
            if (job == null) {
                throw new IllegalStateException("DB 복사 Job 이 사라졌다: " + name);
            }
            if (job.getStatus() != null && ((job.getStatus().getSucceeded() != null && job.getStatus().getSucceeded() > 0)
                    || (job.getStatus().getFailed() != null && job.getStatus().getFailed() > 0))) {
                return job;
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("DB 복사를 기다리다 멈췄다", e);
            }
        }
        throw new IllegalStateException("DB 복사가 " + timeoutSeconds + "초 안에 끝나지 않았다");
    }

    private static boolean succeeded(Job job) {
        return job.getStatus().getSucceeded() != null && job.getStatus().getSucceeded() > 0;
    }

    private String tail(String ns, String name) {
        try {
            String log = k8s.batch().v1().jobs().inNamespace(ns).withName(name).getLog();
            List<String> lines = Arrays.asList(log.split("\n"));
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 15), lines.size()));
        } catch (RuntimeException e) {
            return "(log unavailable: " + e.getMessage() + ")";
        }
    }
}
