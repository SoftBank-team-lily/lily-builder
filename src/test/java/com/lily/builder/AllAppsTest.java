package com.lily.builder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 화면 앱 상태 목록: AWS 클러스터 앱 + GCP 앱. GCP 로 옮긴 앱의 AWS 잔여물(replicas 0)은 '멈춤'으로 보이지 않는다 */
class AllAppsTest {

    private final InMemoryBuildStore store = new InMemoryBuildStore();
    private final ClusterApps cluster = mock(ClusterApps.class);
    private final List<String> asked = new ArrayList<>();
    private final AllApps apps = new AllApps(cluster, store, app -> {
        asked.add(app);
        return "gone".equals(app) ? null : new CicdClient.AppStatus(app, "default", "stable", 2, 2);
    });

    private void build(String id, String app, String... lines) {
        Build build = new Build(id, new BuildRequest("https://github.com/org/blog", null, null, null, app, null, "",
                null, null, Map.of()));
        for (String line : lines) {
            build.log(line);
        }
        build.status(Build.Status.SUCCEEDED, "done");
        store.save(build);
    }

    private static ClusterApps.RunningApp aws(String app, String health) {
        return new ClusterApps.RunningApp(app, "default", "canary", null, health, 0, 0, List.of());
    }

    @Test
    void AWS_앱은_그대로_두고_GCP_앱은_GCP_cicd_상태로_더하며_옮긴_앱의_AWS_잔여물은_뺀다() {
        build("b1", "aws1", "cloudProvider=AWS");
        build("b2", "moved", "cloudProvider=AWS");
        build("b3", "moved", AppMigration.RECORD + "from=AWS to=GCP", "cloudProvider=AWS", "cloudProvider=GCP");
        build("b4", "gcp1", "cloudProvider=GCP");
        build("b5", "gone", "cloudProvider=GCP");
        build("b6", "pc", "queued: x target=onprem agent=abcdefabcdef", "cloudProvider=GCP");
        when(cluster.list()).thenReturn(List.of(aws("aws1", "HEALTHY"), aws("moved", "STOPPED")));

        List<ClusterApps.RunningApp> list = apps.list();

        assertThat(list).extracting(ClusterApps.RunningApp::appName).containsExactlyInAnyOrder("aws1", "moved", "gcp1");
        assertThat(list).filteredOn(app -> app.appName().equals("moved"))
                .singleElement().extracting(ClusterApps.RunningApp::health).isEqualTo("HEALTHY");
        assertThat(asked).containsExactlyInAnyOrder("moved", "gcp1", "gone");
    }

    @Test
    void GCP_가_연결되지_않았으면_AWS_클러스터_앱만_보인다() {
        build("b1", "gcp1", "cloudProvider=GCP");
        when(cluster.list()).thenReturn(List.of(aws("aws1", "HEALTHY")));

        assertThat(new AllApps(cluster, store, (java.util.function.Function<String, CicdClient.AppStatus>) null).list())
                .extracting(ClusterApps.RunningApp::appName).containsExactly("aws1");
    }
}
