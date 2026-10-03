package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** GCP 배포 전에 에이전트로 보내는 cloud-target. 버스트 대상과 DB 터널(정방향·역방향)을 GCP 로 바꾼다 */
class CloudWelcomeTest {

    private static final String KEY = "agent-key";

    private final AgentHub hub = mock(AgentHub.class);
    private final TunnelCertificates certificates = mock(TunnelCertificates.class);
    private final AgentDatabasePorts ports = mock(AgentDatabasePorts.class);

    private CloudWelcome welcome(String tunnelSshHost) {
        CloudProfiles gcp = new CloudProfiles("http://gcp-cicd", "asia-northeast3-docker.pkg.dev/p/lily", "gcp-pull",
                "http://gcp-provisioner", "token", "34.22.68.239", 80, "gcp.lilycloud.kr",
                tunnelSshHost, "lily-tunnel", tunnelSshHost.isBlank() ? "" : "10.20.0.3", 5432, "10.10.0.2");
        return new CloudWelcome(hub, certificates, ports, new CloudClients(gcp));
    }

    private JsonNode sent() throws Exception {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(hub).send(eq(KEY), message.capture());
        return new ObjectMapper().readTree(message.getValue());
    }

    @Test
    void 내_PC_DB_배포도_GCP_배스천으로_정방향과_역방향_터널을_함께_보낸다() throws Exception {
        when(hub.sshPublicKey(KEY)).thenReturn("ssh-ed25519 AAAA lily-agent");
        when(certificates.enabled()).thenReturn(true);
        when(ports.enabled()).thenReturn(true);
        when(ports.portOf(KEY)).thenReturn(20007);
        when(certificates.sign(KEY, "ssh-ed25519 AAAA lily-agent", 20007)).thenReturn("cert");

        welcome("34.64.37.122").retarget(KEY, "blog", false);

        JsonNode message = sent();
        assertThat(message.path("burst").path("ingressHost").asText()).isEqualTo("34.22.68.239");
        JsonNode database = message.path("database");
        assertThat(database.path("sshHost").asText()).isEqualTo("34.64.37.122");
        assertThat(database.path("remoteHost").asText()).isEqualTo("10.20.0.3");
        assertThat(database.path("reverseHost").asText()).isEqualTo("10.10.0.2");
        assertThat(database.path("reversePort").asInt()).isEqualTo(20007);
        assertThat(database.path("certificate").asText()).isEqualTo("cert");
    }

    @Test
    void 내_PC_DB_배포는_GCP_터널이_없으면_버스트_대상만_보낸다() throws Exception {
        when(hub.sshPublicKey(KEY)).thenReturn("ssh-ed25519 AAAA lily-agent");
        when(certificates.enabled()).thenReturn(true);

        welcome("").retarget(KEY, "blog", false);

        JsonNode message = sent();
        assertThat(message.has("burst")).isTrue();
        assertThat(message.has("database")).isFalse();
    }

    @Test
    void 클라우드_DB_배포는_GCP_터널이_없으면_보내지_않고_거절한다() {
        when(hub.sshPublicKey(KEY)).thenReturn("ssh-ed25519 AAAA lily-agent");
        when(certificates.enabled()).thenReturn(true);

        assertThatThrownBy(() -> welcome("").retarget(KEY, "blog", true))
                .hasMessage(CloudWelcome.MISSING_TUNNEL);
        verify(hub, never()).send(anyString(), anyString());
    }
}
