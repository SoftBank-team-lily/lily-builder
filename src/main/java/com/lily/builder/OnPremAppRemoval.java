package com.lily.builder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 온프레미스 앱을 지운다. 예전에는 프로젝트를 지워도 플랫폼 기록만 사라지고 PC 의 컨테이너와 공개 주소가 남아
 * 지운 앱이 계속 응답했다.
 *
 * <ol>
 *   <li>에이전트: 슬롯 컨테이너·이미지, database 면 PC 의 앱 DB ({@link AgentDeployService#remove}). 꺼져 있으면 건너뛴다</li>
 *   <li>공개 주소: {@code {app}.{zone}} 레코드(터널이든 ALB 든)와 에이전트 터널의 호스트 규칙, 엣지 Worker 라우트</li>
 *   <li>클러스터: 버스팅·거점 전환이 만든 클라우드 대기 배포. database 면 클라우드 DB(RDS·Cloud SQL) 도 (lily-cicd 가 provisioner 로)</li>
 * </ol>
 * 에이전트가 거절하면(거점을 옮기는 중 등) 주소와 클러스터는 건드리지 않는다.
 */
@Component
public class OnPremAppRemoval {

    private static final Logger log = LoggerFactory.getLogger(OnPremAppRemoval.class);

    private final AgentDeployService agents;
    private final AgentCloudflare cloudflare;
    private final AppAddress addresses;
    private final EdgeWorker edge;
    private final CloudRouting clouds;

    @Autowired
    public OnPremAppRemoval(AgentDeployService agents, AgentCloudflare cloudflare, AppAddress addresses,
                            EdgeWorker edge, CloudRouting clouds) {
        this.agents = agents;
        this.cloudflare = cloudflare;
        this.addresses = addresses;
        this.edge = edge;
        this.clouds = clouds;
    }

    /**
     * @return 클라우드 앱이면 empty (호출자가 lily-cicd 로 지운다). 온프레미스 앱이면 지운 결과
     * @throws IllegalStateException 에이전트나 lily-cicd 가 거절했다 (거점 전환·배포 중)
     */
    public Optional<Map<String, Object>> remove(String app, boolean database) {
        // 대기 배포는 이 앱의 클라우드(AWS·GCP) 클러스터에 있다. GCP 가 연결되지 않았으면 PC 를 지우기 전에 거절한다
        CicdClient cicd = clouds.cicdOf(app);
        Optional<AgentDeployService.AgentRemoval> agent = agents.remove(app, database);
        if (agent.isEmpty()) {
            return Optional.empty();
        }
        List<String> address = new ArrayList<>();
        if (addresses.enabled()) {
            String host = addresses.host(app);
            quietly(() -> {
                if (addresses.remove(app)) {
                    address.add("record " + host);
                }
            }, "record " + host);
            quietly(() -> {
                if (cloudflare.removeHostname(agent.get().agent(), host)) {
                    address.add("tunnel rule " + host);
                }
            }, "tunnel rule " + host);
        }
        if (edge.enabled()) {
            quietly(() -> edge.detach(app), "edge " + app);
        }
        CicdClient.Passthrough cluster = cicd.remove(app, database);
        if (cluster.status() == 409) {
            throw new IllegalStateException("클라우드 대기 배포가 배포·롤백 중이라 지우지 못했다");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "REMOVED");
        result.put("appName", app);
        result.put("agent", agent.get().status());
        result.put("agentMessage", agent.get().message());
        result.put("address", address);
        result.put("cluster", cluster.status() == 200 ? "removed" : "none");
        log.info("on-prem app removed: app={} result={}", app, result);
        return Optional.of(result);
    }

    /** 주소 정리가 실패해도 나머지는 지운다. 남은 레코드는 응답 없는 주소로 끝난다 */
    private static void quietly(Runnable step, String what) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.warn("remove {} failed: {}", what, e.getMessage());
        }
    }
}
