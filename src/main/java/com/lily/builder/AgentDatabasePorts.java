package com.lily.builder;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 에이전트마다 배스천의 역방향 터널 포트를 하나씩 나눠 준다. 한 번 정한 포트는 바꾸지 않는다.
 *
 * <p>ConfigMap {@code lily-agent-db-ports} (builder namespace) 에 {@code {agent key} → {port}} 로 둔다.
 * 포트가 겹치면 한 에이전트가 다른 에이전트의 클라우드 Pod 접속(계정·비밀번호)을 받게 되므로 반드시 하나씩이다.
 * resourceVersion 을 걸고 고쳐서 동시에 정해도 겹치지 않는다.
 */
@Component
public class AgentDatabasePorts {

    static final String NAME = "lily-agent-db-ports";
    private static final Logger log = LoggerFactory.getLogger(AgentDatabasePorts.class);

    private final KubernetesClient k8s;
    private final String namespace;
    private final PlatformProperties.Tunnel settings;

    public AgentDatabasePorts(KubernetesClient k8s, BuilderProperties builder, PlatformProperties platform) {
        this.k8s = k8s;
        this.namespace = builder.namespace();
        this.settings = platform.tunnel();
    }

    public boolean enabled() {
        return settings.reverseConfigured();
    }

    /** @throws IllegalStateException 범위가 다 찼다 */
    public synchronized int portOf(String agentKey) {
        if (!AgentTokens.KEY.matcher(agentKey).matches()) {
            throw new IllegalArgumentException("에이전트 key 가 아니다");
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            ConfigMap current = k8s.configMaps().inNamespace(namespace).withName(NAME).get();
            Map<String, String> data = current == null || current.getData() == null
                    ? new HashMap<>() : new HashMap<>(current.getData());
            String known = data.get(agentKey);
            if (known != null) {
                return Integer.parseInt(known);
            }
            int port = free(data);
            data.put(agentKey, String.valueOf(port));
            try {
                if (current == null) {
                    k8s.configMaps().inNamespace(namespace).resource(new ConfigMapBuilder()
                            .withNewMetadata().withName(NAME).withNamespace(namespace)
                            .addToLabels("app", "lily-builder").endMetadata()
                            .withData(data).build()).create();
                } else {
                    current.setData(data);
                    k8s.configMaps().inNamespace(namespace).resource(current)
                            .lockResourceVersion(current.getMetadata().getResourceVersion()).update();
                }
                log.info("agent db port assigned: key={} port={}", agentKey, port);
                return port;
            } catch (KubernetesClientException e) {
                // 409: 다른 요청이 먼저 고쳤다. 다시 읽고 정한다
                if (e.getCode() != 409) {
                    throw e;
                }
            }
        }
        throw new IllegalStateException("역방향 터널 포트를 정하지 못했다 (동시 수정)");
    }

    private int free(Map<String, String> data) {
        Set<String> used = new HashSet<>(data.values());
        for (int port = settings.reversePortFrom(); port <= settings.reversePortTo(); port++) {
            if (!used.contains(String.valueOf(port))) {
                return port;
            }
        }
        throw new IllegalStateException("역방향 터널 포트가 다 찼다: "
                + settings.reversePortFrom() + "-" + settings.reversePortTo());
    }
}
