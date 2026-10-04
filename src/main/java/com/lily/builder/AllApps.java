package com.lily.builder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 화면의 앱 상태 목록 ({@code GET /api/apps}). AWS 클러스터 앱({@link ClusterApps})에 GCP 앱 상태를 더한다.
 *
 * <p>GCP 클러스터는 builder 가 직접 읽지 못해서 앱마다 GCP lily-cicd 에 묻는다. 어느 앱이 GCP 인지는 배포 기록으로 정한다
 * ({@link AgentDeployService#cloudProvider} 와 같은 규칙). GCP 로 옮긴 앱은 AWS 에 replicas 0 으로 남아 있어도
 * (옮기기 HOLD) AWS 쪽 항목을 빼서 '멈춤'으로 보이지 않게 한다. 내 PC 앱은 지금처럼 다루지 않는다.
 */
@Component
public class AllApps {

    private static final Logger log = LoggerFactory.getLogger(AllApps.class);
    /** GCP cicd 가 느려도 화면 목록은 기다리지 않는다 */
    private static final long GCP_TIMEOUT_MS = 3000;

    private final ClusterApps cluster;
    private final BuildStore store;
    private final Function<String, CicdClient.AppStatus> gcpStatus;

    @Autowired
    public AllApps(ClusterApps cluster, BuildStore store, CloudClients clouds) {
        this(cluster, store, clouds.deployConfigured() ? app -> clouds.cicd().status(app) : null);
    }

    AllApps(ClusterApps cluster, BuildStore store, Function<String, CicdClient.AppStatus> gcpStatus) {
        this.cluster = cluster;
        this.store = store;
        this.gcpStatus = gcpStatus;
    }

    public List<ClusterApps.RunningApp> list() {
        Map<String, String> providers = cloudApps();
        List<ClusterApps.RunningApp> apps = new ArrayList<>(cluster.list().stream()
                .filter(app -> !"GCP".equals(providers.get(app.appName())))
                .toList());
        if (gcpStatus != null) {
            List<CompletableFuture<ClusterApps.RunningApp>> asked = providers.entrySet().stream()
                    .filter(e -> "GCP".equals(e.getValue()))
                    .map(e -> CompletableFuture.supplyAsync(() -> gcpApp(e.getKey()))
                            .completeOnTimeout(null, GCP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .exceptionally(error -> {
                                log.debug("gcp app status failed: app={} {}", e.getKey(), error.getMessage());
                                return null;
                            }))
                    .toList();
            asked.stream().map(CompletableFuture::join).filter(Objects::nonNull).forEach(apps::add);
        }
        apps.sort(Comparator.comparing(ClusterApps.RunningApp::namespace).thenComparing(ClusterApps.RunningApp::appName));
        return apps;
    }

    /** 클라우드 앱 이름 → 지금 클라우드. 내 PC 로 배포한 적 있는 앱과 옮기는 중에 만든 빌드는 뺀다 */
    Map<String, String> cloudApps() {
        Map<String, Build> latest = new HashMap<>();
        Map<String, Boolean> onPrem = new HashMap<>();
        for (Build build : store.findAll()) {
            if (build.getAppName() == null || build.getLogs().stream().anyMatch(l -> l.startsWith(AppMigration.PART))) {
                continue;
            }
            if (AgentDeployService.onPrem(build)
                    || build.getLogs().stream().anyMatch(l -> l.startsWith(AgentBurst.STANDBY_MARK))) {
                onPrem.put(build.getAppName(), true);
            }
            latest.merge(build.getAppName(), build,
                    (a, b) -> a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b);
        }
        Map<String, String> providers = new HashMap<>();
        latest.forEach((app, build) -> {
            if (!onPrem.containsKey(app)) {
                providers.put(app, AgentDeployService.lastCloudProvider(build));
            }
        });
        return providers;
    }

    private ClusterApps.RunningApp gcpApp(String app) {
        CicdClient.AppStatus status = gcpStatus.apply(app);
        if (status == null) {
            return null;
        }
        int ready = status.readyReplicas();
        int desired = status.replicas();
        String health = desired == 0 ? "STOPPED" : ready >= desired ? "HEALTHY" : ready == 0 ? "DOWN" : "DEGRADED";
        return new ClusterApps.RunningApp(app, "gcp/" + status.namespace(), "canary", null, health, ready, desired,
                List.of());
    }
}
