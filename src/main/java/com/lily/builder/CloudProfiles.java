package com.lily.builder;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 하이브리드에서 고른 GCP 데이터 플레인. 비어 있으면 GCP 프로젝트는 배포 전에 거절하고 AWS 로 보내지 않는다.
 * 컨트롤 플레인(builder, Cloudflare 존)은 AWS 에 그대로 둔다.
 *
 * @param cicdUrl         GCP 클러스터의 lily-cicd
 * @param registry        Artifact Registry 호스트/경로. 예: {@code asia-northeast3-docker.pkg.dev/proj/lily}
 * @param registrySecret  빌더 클러스터의 dockerconfigjson 시크릿 이름 (Kaniko 가 푸시할 때)
 * @param provisionerUrl  Cloud SQL 을 만드는 provisioner
 * @param provisionerToken 그 provisioner 의 Bearer 토큰
 * @param ingressHost     버스팅이 넘길 GCP Ingress
 * @param ingressPort     그 Ingress 포트
 * @param cloudOrigin     거점·장애 전환 CNAME 내용물 (GCP 로드밸런서)
 * @param tunnelSshHost   Cloud SQL 로 permitopen 된 배스천
 * @param tunnelSshUser   배스천 SSH 계정
 * @param tunnelRemoteHost Cloud SQL 사설 IP
 * @param tunnelRemotePort Cloud SQL 포트
 * @param tunnelReverseHost 역방향 터널이 붙는 배스천 사설 IP. 비우면 내 PC DB 를 GCP 대기 Pod 에 열지 않는다
 */
@ConfigurationProperties("lily.builder.gcp")
public record CloudProfiles(
        @DefaultValue("") String cicdUrl,
        @DefaultValue("") String registry,
        @DefaultValue("gcp-pull") String registrySecret,
        @DefaultValue("") String provisionerUrl,
        @DefaultValue("") String provisionerToken,
        @DefaultValue("") String ingressHost,
        @DefaultValue("80") int ingressPort,
        @DefaultValue("") String cloudOrigin,
        @DefaultValue("") String tunnelSshHost,
        @DefaultValue("lily-tunnel") String tunnelSshUser,
        @DefaultValue("") String tunnelRemoteHost,
        @DefaultValue("5432") int tunnelRemotePort,
        @DefaultValue("") String tunnelReverseHost) {

    public boolean deployConfigured() {
        return !cicdUrl.isBlank() && !registry.isBlank();
    }

    public boolean provisionerConfigured() {
        return !provisionerUrl.isBlank() && !provisionerToken.isBlank();
    }

    public boolean burstConfigured() {
        return !ingressHost.isBlank() && !origin().isBlank();
    }

    public boolean tunnelConfigured() {
        return !tunnelSshHost.isBlank() && !tunnelRemoteHost.isBlank();
    }

    /** CNAME 비교용. 끝의 점과 대소문자를 없앤다 */
    public String origin() {
        String value = cloudOrigin == null ? "" : cloudOrigin.trim().toLowerCase();
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }
}
