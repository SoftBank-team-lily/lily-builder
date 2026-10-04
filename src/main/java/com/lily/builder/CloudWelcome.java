package com.lily.builder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GCP 프로젝트의 버스팅 Ingress·CNAME·DB 터널을 에이전트에 다시 알린다.
 * 연결 직후 welcome 은 아직 앱이 없어 AWS 값을 싣는다. 배포를 보내기 전에 이 앱의 GCP 값으로 바꾼다.
 */
@Component
public class CloudWelcome {

    static final String MISSING_BURST =
            "GCP 클라우드 연결이 부족하다 (GCP_CICD_URL, GCP_REGISTRY, GCP_BURST_INGRESS_HOST, GCP_CUTOVER_ORIGIN)";
    static final String MISSING_TUNNEL =
            "GCP DB 터널이 연결되지 않았다 (GCP_TUNNEL_SSH_HOST, GCP_TUNNEL_REMOTE_HOST)";

    private final AgentHub hub;
    private final TunnelCertificates certificates;
    private final AgentDatabasePorts ports;
    private final CloudClients clouds;
    /** AWS 버스트 Ingress·오리진. null 이면 AWS 로 되돌리지 않는다 (테스트) */
    private final PlatformProperties platform;
    private final ObjectMapper json = new ObjectMapper();

    CloudWelcome(AgentHub hub, TunnelCertificates certificates, AgentDatabasePorts ports, CloudClients clouds) {
        this(hub, certificates, ports, clouds, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CloudWelcome(AgentHub hub, TunnelCertificates certificates, AgentDatabasePorts ports, CloudClients clouds,
                        PlatformProperties platform) {
        this.platform = platform;
        this.hub = hub;
        this.certificates = certificates;
        this.ports = ports;
        this.clouds = clouds;
    }

    /**
     * DB 터널(정방향 Cloud SQL, 역방향 내 PC DB)도 GCP 배스천으로 바꾼다. 클라우드 DB 를 쓰지 않는 배포(내 PC DB)도
     * GCP 대기 Pod 가 역방향 터널로 PC DB 에 붙고, 거점 전환의 DB 이전이 Cloud SQL 로 가야 해서 함께 보낸다.
     *
     * @param databaseTunnel 이 배포가 클라우드 DB 터널을 쓴다. 그러면 배스천·Cloud SQL 주소가 꼭 있어야 한다
     */
    public void retarget(String key, String app, boolean databaseTunnel) {
        CloudProfiles gcp = clouds.profile();
        if (!gcp.deployConfigured() || !gcp.burstConfigured()) {
            throw new IllegalStateException(MISSING_BURST);
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "cloud-target");
        Map<String, Object> burst = new LinkedHashMap<>();
        burst.put("ingressHost", gcp.ingressHost());
        burst.put("ingressPort", gcp.ingressPort());
        burst.put("cloudOrigin", gcp.origin());
        burst.put("app", app);
        message.put("burst", burst);
        String publicKey = hub.sshPublicKey(key);
        if (databaseTunnel) {
            if (!gcp.tunnelConfigured() || !certificates.enabled()) {
                throw new IllegalStateException(MISSING_TUNNEL);
            }
            if (publicKey.isBlank()) {
                throw new IllegalStateException("에이전트가 DB 터널 키를 보내지 않았다. 에이전트를 다시 실행한다");
            }
        }
        if (gcp.tunnelConfigured() && certificates.enabled() && !publicKey.isBlank()) {
            Map<String, Object> database = new LinkedHashMap<>();
            database.put("sshHost", gcp.tunnelSshHost());
            database.put("sshUser", gcp.tunnelSshUser());
            database.put("remoteHost", gcp.tunnelRemoteHost());
            database.put("remotePort", gcp.tunnelRemotePort());
            Integer reversePort = null;
            if (ports.enabled() && !gcp.tunnelReverseHost().isBlank()) {
                reversePort = ports.portOf(key);
                database.put("reverseHost", gcp.tunnelReverseHost());
                database.put("reversePort", reversePort);
            }
            database.put("certificate", certificates.sign(key, publicKey, reversePort));
            message.put("database", database);
        }
        try {
            hub.send(key, json.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("GCP 클라우드 주소를 에이전트에 보내지 못했다");
        }
    }

    /**
     * AWS 앱을 배포하기 전에 에이전트의 버스트 Ingress·CNAME·DB 터널을 AWS 값으로 되돌린다.
     * 같은 에이전트에서 GCP 앱을 배포했거나, 다시 붙을 때 welcome 이 GCP 값을 줬으면 에이전트는 계속 GCP 를 본다.
     * 그대로 두면 AWS 대기 Pod 가 GCP 배스천의 역방향 터널 주소를 받아 PC DB 에 붙지 못한다. 이미 AWS 값이면 에이전트는 바꾸지 않는다.
     *
     * @return 보냈으면 true. AWS 버스트·터널이 설정되지 않았으면 보내지 않는다
     */
    public boolean retargetAws(String key, String app) {
        if (platform == null) {
            return false;
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "cloud-target");
        PlatformProperties.Burst aws = platform.burst();
        if (aws.configured()) {
            Map<String, Object> burst = new LinkedHashMap<>();
            burst.put("ingressHost", aws.ingressHost());
            burst.put("ingressPort", aws.ingressPort());
            burst.put("cloudOrigin", aws.origin());
            burst.put("app", app);
            message.put("burst", burst);
        }
        String publicKey = hub.sshPublicKey(key);
        if (certificates.enabled() && !publicKey.isBlank()) {
            PlatformProperties.Tunnel tunnel = certificates.settings();
            Map<String, Object> database = new LinkedHashMap<>();
            database.put("sshHost", tunnel.sshHost());
            database.put("sshUser", tunnel.sshUser());
            database.put("remoteHost", tunnel.remoteHost());
            database.put("remotePort", tunnel.remotePort());
            Integer reversePort = null;
            if (ports.enabled() && !tunnel.reverseHost().isBlank()) {
                reversePort = ports.portOf(key);
                database.put("reverseHost", tunnel.reverseHost());
                database.put("reversePort", reversePort);
            }
            database.put("certificate", certificates.sign(key, publicKey, reversePort));
            message.put("database", database);
        }
        if (message.size() == 1) {
            return false;
        }
        try {
            hub.send(key, json.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AWS 클라우드 주소를 에이전트에 보내지 못했다");
        }
        return true;
    }
}
