package com.lily.builder;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 플랫폼 연결: 사용자 PC 의 에이전트에는 에이전트 토큰만 두고, 공개 주소와 DB 터널에 필요한 자격은 builder 가 쥔다.
 *
 * @param cloudflare 플랫폼 존. 에이전트의 Cloudflare 호출을 여기서 대신 한다 ({@link AgentCloudflare})
 * @param tunnel     DB 터널. 에이전트 공개키에 SSH CA 로 단기 인증서를 서명해 준다 ({@link TunnelCertificates})
 */
@ConfigurationProperties("lily.builder.platform")
public record PlatformProperties(
        @DefaultValue Cloudflare cloudflare,
        @DefaultValue Tunnel tunnel) {

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
     */
    public record Tunnel(
            @DefaultValue("") String sshHost,
            @DefaultValue("lily-tunnel") String sshUser,
            @DefaultValue("") String remoteHost,
            @DefaultValue("5432") int remotePort,
            @DefaultValue("") String caKey,
            @DefaultValue("24") int validityHours) {

        public boolean configured() {
            return !sshHost.isBlank() && !remoteHost.isBlank() && !caKey.isBlank();
        }
    }
}
