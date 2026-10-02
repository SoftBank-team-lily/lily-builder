package com.lily.builder;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 플랫폼 연결: 사용자 PC 의 에이전트에는 에이전트 토큰만 두고, 공개 주소와 DB 터널에 필요한 자격은 builder 가 쥔다.
 *
 * @param cloudflare 플랫폼 존. 에이전트의 Cloudflare 호출을 여기서 대신 한다 ({@link AgentCloudflare})
 * @param tunnel     DB 터널. 에이전트 공개키에 SSH CA 로 단기 인증서를 서명해 준다 ({@link TunnelCertificates})
 * @param burst      클라우드 버스팅·거점 전환. 에이전트가 넘길 Ingress 와 거점이 클라우드일 때의 CNAME 내용물
 * @param edge       PC 장애 때 엣지에서 요청을 클라우드로 다시 보내는 Worker ({@link EdgeWorker})
 */
@ConfigurationProperties("lily.builder.platform")
public record PlatformProperties(
        @DefaultValue Cloudflare cloudflare,
        @DefaultValue Tunnel tunnel,
        @DefaultValue Burst burst,
        @DefaultValue Edge edge) {

    /**
     * @param enabled    켜면 기동할 때 Worker 를 올리고, 클라우드 대기 배포가 끝난 앱에 라우트를 건다.
     *                   토큰에 Workers Scripts·Workers Routes 편집 권한이 있어야 한다
     * @param scriptName Cloudflare 의 Worker 이름
     */
    public record Edge(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("lily-edge") String scriptName) {
    }

    /**
     * @param ingressHost 에이전트 프록시가 넘긴 요청을 받는 클러스터 Ingress (공개 IP 또는 호스트, http)
     * @param ingressPort 그 포트
     * @param cloudOrigin 거점이 클라우드일 때 앱 CNAME 이 가리키는 호스트 (클러스터 앞의 ALB). 비우면 거점 전환을 주지 않는다
     */
    public record Burst(
            @DefaultValue("") String ingressHost,
            @DefaultValue("80") int ingressPort,
            @DefaultValue("") String cloudOrigin) {

        public boolean configured() {
            return !ingressHost.isBlank();
        }

        /** 끝의 점과 대소문자를 뺀 오리진 */
        public String origin() {
            String value = cloudOrigin.trim().toLowerCase();
            return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
        }
    }

    public record Cloudflare(
            @DefaultValue("") String apiToken,
            @DefaultValue("") String accountId,
            @DefaultValue("") String zoneId,
            @DefaultValue("") String zoneName) {

        public boolean configured() {
            return !apiToken.isBlank() && !accountId.isBlank() && !zoneId.isBlank() && !zoneName.isBlank();
        }
    }

    /**
     * @param caKey         SSH CA 개인키 (OpenSSH PEM 본문). 배스천의 lily-tunnel authorized_keys 에 cert-authority 로 등록한 키
     * @param validityHours 인증서 유효 시간. 에이전트는 다시 연결할 때마다 새로 받는다
     * @param reverseHost   역방향 터널이 열리는 배스천의 사설 IP. 클라우드 Pod 가 이 주소로 온프레미스 DB 에 붙는다.
     *                      비우면 역방향 터널을 주지 않는다 (온프레미스 DB 앱은 클라우드 대기 배포를 하지 못한다)
     * @param reversePortFrom 에이전트마다 하나씩 나눠 주는 포트 범위 (배스천 보안그룹에서 VPC 에만 연다)
     */
    public record Tunnel(
            @DefaultValue("") String sshHost,
            @DefaultValue("lily-tunnel") String sshUser,
            @DefaultValue("") String remoteHost,
            @DefaultValue("5432") int remotePort,
            @DefaultValue("") String caKey,
            @DefaultValue("24") int validityHours,
            @DefaultValue("") String reverseHost,
            @DefaultValue("20000") int reversePortFrom,
            @DefaultValue("20999") int reversePortTo) {

        public boolean configured() {
            return !sshHost.isBlank() && !remoteHost.isBlank() && !caKey.isBlank();
        }

        public boolean reverseConfigured() {
            return configured() && !reverseHost.isBlank() && reversePortFrom > 0 && reversePortTo >= reversePortFrom;
        }
    }
}
