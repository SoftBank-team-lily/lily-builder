package com.lily.builder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 앱이 배포된 클라우드의 lily-cicd 와 공개 주소 CNAME 을 고른다. 클라우드는 이 앱의 가장 최근 빌드에 남은
 * {@code cloudProvider=} 로 정한다 ({@link AgentDeployService#cloudProvider}). 기록이 없으면 AWS.
 */
@Component
public class CloudRouting {

    private final AgentDeployService deploys;
    private final CicdClient aws;
    /** GCP lily-cicd. 연결되지 않았으면 null */
    private final CicdClient gcp;
    /** GCP 로드밸런서 CNAME. 연결되지 않았으면 빈 값 */
    private final String gcpOrigin;

    @Autowired
    public CloudRouting(AgentDeployService deploys, CicdClient cicd, CloudClients clouds) {
        this(deploys, cicd, clouds.deployConfigured() ? clouds.cicd() : null, clouds.origin());
    }

    CloudRouting(AgentDeployService deploys, CicdClient aws, CicdClient gcp, String gcpOrigin) {
        this.deploys = deploys;
        this.aws = aws;
        this.gcp = gcp;
        this.gcpOrigin = gcpOrigin == null ? "" : gcpOrigin;
    }

    /** 모든 앱을 AWS 로 본다 (테스트 생성자) */
    static CloudRouting awsOnly(CicdClient cicd) {
        return new CloudRouting(null, cicd, null, "");
    }

    /** GCP(DB) 와 AWS 에 같이 떠 있는 앱 ({@link BuildService} 멀티클라우드 배포) */
    public boolean multi(String app) {
        return deploys != null && deploys.multiCloud(app);
    }

    /** 멀티클라우드 앱의 AWS 쪽 lily-cicd (DB 없이 GCP DB 를 따라가는 클러스터) */
    public CicdClient awsCicd() {
        return aws;
    }

    public boolean gcp(String app) {
        return deploys != null && "GCP".equals(deploys.cloudProvider(app));
    }

    /** @throws Unconfigured GCP 앱인데 GCP lily-cicd 주소가 없다 */
    public CicdClient cicdOf(String app) {
        if (!gcp(app)) {
            return aws;
        }
        if (gcp == null) {
            throw new Unconfigured(CloudClients.MISSING);
        }
        return gcp;
    }

    /** 주소를 클라우드로 둘 때의 CNAME 내용물. AWS 면 null ({@link AppAddress} 기본값 ALB) */
    public String originOf(String app) {
        if (!gcp(app)) {
            return null;
        }
        if (gcpOrigin.isBlank()) {
            throw new Unconfigured("GCP 클라우드 주소가 연결되지 않았다 (GCP_CUTOVER_ORIGIN)");
        }
        return gcpOrigin;
    }

    public static class Unconfigured extends IllegalStateException {
        public Unconfigured(String message) {
            super(message);
        }
    }
}
