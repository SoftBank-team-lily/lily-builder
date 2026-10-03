package com.lily.builder;

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

    public CloudClients(CloudProfiles profile) {
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
