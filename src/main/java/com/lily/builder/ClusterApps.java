package com.lily.builder;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * k3s 에 지금 떠 있는 앱. lily-cicd 가 만드는 이름 규칙을 읽는다.
 *
 * <ul>
 *   <li>Service {@code {app}-svc}, selector 에 {@code app}. blue-green 은 {@code color} 도 있다</li>
 *   <li>Deployment {@code {app}-{slot}}, 라벨 {@code app} + {@code color}(blue-green) 또는 {@code track}(canary)</li>
 *   <li>Ingress {@code {app}-ingress} 의 host 가 접속 주소</li>
 * </ul>
 */
@Component
public class ClusterApps {

    /** 플랫폼 자체와 시스템 namespace 는 사용자 앱이 아니다 */
    private static final Set<String> SYSTEM_NAMESPACES =
            Set.of("kube-system", "kube-public", "kube-node-lease", "lily-system", "lily-builds");

    private final KubernetesClient k8s;

    public ClusterApps(KubernetesClient k8s) {
        this.k8s = k8s;
    }

    public List<RunningApp> list() {
        return k8s.services().inAnyNamespace().list().getItems().stream()
                .filter(svc -> !SYSTEM_NAMESPACES.contains(svc.getMetadata().getNamespace()))
                .map(this::toApp)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(RunningApp::namespace).thenComparing(RunningApp::appName))
                .toList();
    }

    private RunningApp toApp(Service svc) {
        Map<String, String> selector = svc.getSpec() == null ? null : svc.getSpec().getSelector();
        String app = selector == null ? null : selector.get("app");
        if (app == null || !svc.getMetadata().getName().equals(app + "-svc")) {
            return null;
        }
        String ns = svc.getMetadata().getNamespace();
        String activeColor = selector.get("color");
        String strategy = activeColor != null ? "blue-green" : "canary";

        List<Slot> slots = k8s.apps().deployments().inNamespace(ns).withLabel("app", app).list().getItems().stream()
                .map(d -> toSlot(d, activeColor))
                .sorted(Comparator.comparing(Slot::name))
                .toList();
        Ingress ingress = k8s.network().v1().ingresses().inNamespace(ns).withName(app + "-ingress").get();
        String host = ingress == null || ingress.getSpec().getRules() == null || ingress.getSpec().getRules().isEmpty()
                ? null : ingress.getSpec().getRules().get(0).getHost();

        int ready = slots.stream().filter(Slot::serving).mapToInt(Slot::readyReplicas).sum();
        int desired = slots.stream().filter(Slot::serving).mapToInt(Slot::replicas).sum();
        String health = desired == 0 ? "STOPPED" : ready == desired ? "HEALTHY" : ready == 0 ? "DOWN" : "DEGRADED";
        return new RunningApp(app, ns, strategy, host == null ? null : "http://" + host, health, ready, desired, slots);
    }

    private static Slot toSlot(Deployment d, String activeColor) {
        Map<String, String> labels = d.getMetadata().getLabels();
        String slot = labels.containsKey("color") ? labels.get("color") : labels.getOrDefault("track", "");
        int replicas = d.getSpec().getReplicas() == null ? 0 : d.getSpec().getReplicas();
        int ready = d.getStatus() == null || d.getStatus().getReadyReplicas() == null ? 0 : d.getStatus().getReadyReplicas();
        // blue-green 은 selector 색만 트래픽을 받고, canary 는 Pod 가 있는 슬롯이 모두 받는다
        boolean serving = activeColor != null ? activeColor.equals(slot) : replicas > 0;
        List<Container> containers = d.getSpec().getTemplate().getSpec().getContainers();
        String image = containers.isEmpty() ? null : containers.get(0).getImage();
        String version = containers.isEmpty() ? null : containers.get(0).getEnv().stream()
                .filter(e -> "APP_VERSION".equals(e.getName())).map(e -> e.getValue()).findFirst().orElse(null);
        return new Slot(d.getMetadata().getName(), slot, serving, image, version, replicas, ready);
    }

    /**
     * @param health HEALTHY (트래픽 받는 Pod 전부 Ready), DEGRADED (일부), DOWN (0개), STOPPED (0개로 줄여 둠)
     */
    public record RunningApp(String appName, String namespace, String strategy, String url, String health,
                             int readyReplicas, int replicas, List<Slot> slots) {}

    /** @param serving 지금 트래픽을 받는 슬롯인지 */
    public record Slot(String name, String slot, boolean serving, String image, String version,
                       int replicas, int readyReplicas) {}
}
