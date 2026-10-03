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
    private final ObjectMapper json = new ObjectMapper();

    public CloudWelcome(AgentHub hub, TunnelCertificates certificates, AgentDatabasePorts ports, CloudClients clouds) {
        this.hub = hub;
        this.certificates = certificates;
        this.ports = ports;
        this.clouds = clouds;
    }

    /**
     * @param databaseTunnel 이 배포가 클라우드 DB 터널을 쓴다. 그러면 배스천·Cloud SQL 주소가 있어야 한다
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
        if (databaseTunnel) {
            if (!gcp.tunnelConfigured() || !certificates.enabled()) {
                throw new IllegalStateException(MISSING_TUNNEL);
            }
            String publicKey = hub.sshPublicKey(key);
            if (publicKey.isBlank()) {
                throw new IllegalStateException("에이전트가 DB 터널 키를 보내지 않았다. 에이전트를 다시 실행한다");
            }
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
}
