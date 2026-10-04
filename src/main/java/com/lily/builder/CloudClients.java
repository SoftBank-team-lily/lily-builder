package com.lily.builder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GCP 데이터 플레인 클라이언트. 프로세스 시작 때 주소가 비어 있으면 만들지 않고,
 * GCP 를 고른 배포가 그 주소를 쓰는 순간에 거절한다. AWS 클라이언트는 기존 빈을 그대로 쓴다.
 */
@Component
public class CloudClients {

    static final String MISSING = "GCP 클라우드가 연결되지 않았다 (GCP_CICD_URL, GCP_REGISTRY)";
    static final String MISSING_DB = "GCP DB 가 연결되지 않았다 (GCP_PROVISIONER_URL, GCP_PROVISIONER_API_TOKEN)";

    private final CloudProfiles profile;
    private final CicdClient cicd;
    private final ProvisionerClient provisioner;
    /** AWS 클러스터에서 Cloud SQL 로 가는 DB 릴레이 (멀티클라우드 앱의 AWS 쪽 Pod 가 붙는다) */
    private final String relayHost;
    private final int relayPort;

    public CloudClients(CloudProfiles profile) {
        this(profile, "db-relay-gcp.lily-builds.svc.cluster.local", 5432);
    }

    @Autowired
    public CloudClients(CloudProfiles profile,
                        @Value("${lily.builder.gcp.relay-host:db-relay-gcp.lily-builds.svc.cluster.local}") String relayHost,
                        @Value("${lily.builder.gcp.relay-port:5432}") int relayPort) {
        this.relayHost = relayHost;
        this.relayPort = relayPort;
        this.profile = profile;
        this.cicd = profile.deployConfigured() ? CicdClient.forUrl(profile.cicdUrl()) : null;
        this.provisioner = profile.provisionerConfigured()
                ? new ProvisionerClient(new ProvisionerClient.Settings(profile.provisionerUrl(), profile.provisionerToken()))
                : null;
    }

    public CloudProfiles profile() {
        return profile;
    }

    public String origin() {
        return profile.origin();
    }

    public String registry() {
        return profile.registry();
    }

    public String registrySecret() {
        return profile.registrySecret().isBlank() ? "gcp-pull" : profile.registrySecret();
    }

    public String relayHost() {
        return relayHost;
    }

    public int relayPort() {
        return relayPort;
    }

    public boolean deployConfigured() {
        return cicd != null;
    }

    public CicdClient cicd() {
        if (cicd == null) {
            throw new IllegalStateException(MISSING);
        }
        return cicd;
    }

    public ProvisionerClient provisioner() {
        if (provisioner == null) {
            throw new IllegalStateException(MISSING_DB);
        }
        return provisioner;
    }
}
