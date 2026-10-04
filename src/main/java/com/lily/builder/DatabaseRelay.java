package com.lily.builder;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 멀티클라우드 앱의 AWS 쪽 Pod 가 GCP Cloud SQL 에 붙는 DB 릴레이의 키와 인증서.
 *
 * <p>릴레이는 AWS 클러스터의 Deployment {@code db-relay-gcp} 다 (lily-db-provisioner deploy/k3s/cluster/db-relay-gcp.yaml).
 * {@code ssh -L 0.0.0.0:5432:{Cloud SQL}} 을 GCP 배스천({@code lily-tunnel})으로 열고, 끊기면 다시 연다.
 * 이 클래스는 그 Pod 가 읽는 Secret {@code lily-builds/db-relay-gcp} 만 관리한다: 키는 처음 한 번 만들고, 인증서는
 * {@link #VALID_HOURS} 짜리로 {@link #RENEW_HOURS} 마다 다시 서명한다. 열린 ssh 는 인증서가 만료돼도 끊기지 않고,
 * 다시 붙을 때 새 인증서를 쓴다.
 */
@Component
public class DatabaseRelay {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRelay.class);

    static final String SECRET = "db-relay-gcp";
    static final int VALID_HOURS = 24;
    static final int RENEW_HOURS = 6;

    private final KubernetesClient k8s;
    private final BuilderProperties props;
    private final TunnelCertificates certificates;
    private final CloudProfiles gcp;

    @Autowired
    public DatabaseRelay(KubernetesClient k8s, BuilderProperties props, TunnelCertificates certificates,
                         CloudClients clouds) {
        this.k8s = k8s;
        this.props = props;
        this.certificates = certificates;
        this.gcp = clouds.profile();
    }

    /** GCP 배스천과 Cloud SQL 주소, 터널 CA 가 있다 */
    public boolean enabled() {
        return certificates.enabled() && gcp.tunnelConfigured();
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT" + RENEW_HOURS + "H")
    public void renewQuietly() {
        if (!enabled()) {
            return;
        }
        try {
            renew();
        } catch (RuntimeException e) {
            log.warn("db relay certificate renew failed: {}", e.getMessage());
        }
    }

    /** 키가 없으면 만들고, 인증서를 새로 서명해 Secret 에 둔다 */
    void renew() {
        String ns = props.namespace();
        Secret existing = k8s.secrets().inNamespace(ns).withName(SECRET).get();
        String privateKey = value(existing, "id");
        TunnelCertificates.JobKey key = certificates.relayKey("gcp", privateKey, VALID_HOURS);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("id", key.privateKey());
        data.put("id-cert.pub", key.certificate());
        data.put("TUNNEL_HOST", gcp.tunnelSshHost());
        data.put("TUNNEL_USER", gcp.tunnelSshUser());
        data.put("TUNNEL_REMOTE", gcp.tunnelRemoteHost() + ":" + gcp.tunnelRemotePort());
        if (existing == null) {
            k8s.secrets().inNamespace(ns).resource(new SecretBuilder()
                    .withNewMetadata().withName(SECRET).addToLabels("app", SECRET).endMetadata()
                    .withStringData(data)
                    .build()).create();
            log.info("db relay key created: {}/{}", ns, SECRET);
        } else {
            k8s.secrets().inNamespace(ns).withName(SECRET).edit(secret -> new SecretBuilder(secret)
                    .withData(null)
                    .withStringData(data)
                    .build());
            log.info("db relay certificate renewed: {}/{} ({}h)", ns, SECRET, VALID_HOURS);
        }
    }

    private static String value(Secret secret, String key) {
        if (secret == null || secret.getData() == null || secret.getData().get(key) == null) {
            return null;
        }
        return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
    }
}
