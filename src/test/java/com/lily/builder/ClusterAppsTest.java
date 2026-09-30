package com.lily.builder;

import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** lily-cicd 가 만든 리소스를 앱 단위로 묶는다 */
@EnableKubernetesMockClient(crud = true)
class ClusterAppsTest {

    KubernetesClient client;

    @Test
    void blue_green_은_selector_색만_트래픽을_받는다() {
        service("default", "blog-svc", Map.of("app", "blog", "color", "green"));
        deployment("default", "blog-blue", Map.of("app", "blog", "color", "blue"), "reg/blog:1", 0, 0);
        deployment("default", "blog-green", Map.of("app", "blog", "color", "green"), "reg/blog:2", 1, 1);
        client.network().v1().ingresses().inNamespace("default").resource(new IngressBuilder()
                .withNewMetadata().withName("blog-ingress").endMetadata()
                .withNewSpec().addNewRule().withHost("blog.domain.com").endRule().endSpec().build()).create();

        ClusterApps.RunningApp app = new ClusterApps(client).list().get(0);

        assertThat(app.appName()).isEqualTo("blog");
        assertThat(app.strategy()).isEqualTo("blue-green");
        assertThat(app.url()).isEqualTo("http://blog.domain.com");
        assertThat(app.health()).isEqualTo("HEALTHY");
        assertThat(app.slots()).extracting(ClusterApps.Slot::slot, ClusterApps.Slot::serving, ClusterApps.Slot::image)
                .containsExactly(tuple("blue", false, "reg/blog:1"), tuple("green", true, "reg/blog:2"));
    }

    @Test
    void canary_는_Pod_있는_슬롯이_모두_받고_일부만_Ready_면_DEGRADED() {
        service("apps", "shop-svc", Map.of("app", "shop"));
        deployment("apps", "shop-stable", Map.of("app", "shop", "track", "stable"), "reg/shop:1", 4, 4);
        deployment("apps", "shop-canary", Map.of("app", "shop", "track", "canary"), "reg/shop:2", 1, 0);

        ClusterApps.RunningApp app = new ClusterApps(client).list().get(0);

        assertThat(app.strategy()).isEqualTo("canary");
        assertThat(app.health()).isEqualTo("DEGRADED");
        assertThat(app.readyReplicas()).isEqualTo(4);
        assertThat(app.replicas()).isEqualTo(5);
        assertThat(app.url()).isNull();
    }

    @Test
    void 플랫폼_namespace_와_규칙에_안_맞는_Service_는_뺀다() {
        service("lily-system", "lily-cicd-svc", Map.of("app", "lily-cicd"));
        service("default", "other", Map.of("app", "something-else"));
        client.services().inNamespace("default").resource(new ServiceBuilder()
                .withNewMetadata().withName("kubernetes").endMetadata().withNewSpec().endSpec().build()).create();

        assertThat(new ClusterApps(client).list()).isEmpty();
    }

    private void service(String ns, String name, Map<String, String> selector) {
        client.services().inNamespace(ns).resource(new ServiceBuilder()
                .withNewMetadata().withName(name).endMetadata()
                .withNewSpec().withSelector(selector).endSpec().build()).create();
    }

    private void deployment(String ns, String name, Map<String, String> labels, String image, int replicas, int ready) {
        Deployment d = new DeploymentBuilder()
                .withNewMetadata().withName(name).withLabels(labels).endMetadata()
                .withNewSpec().withReplicas(replicas)
                    .withNewTemplate().withNewSpec()
                        .addNewContainer().withName("app").withImage(image).endContainer()
                    .endSpec().endTemplate()
                .endSpec()
                .withNewStatus().withReadyReplicas(ready).endStatus()
                .build();
        client.apps().deployments().inNamespace(ns).resource(d).create();
    }
}
