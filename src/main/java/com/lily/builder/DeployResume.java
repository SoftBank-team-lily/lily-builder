package com.lily.builder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * builder Pod 가 배포 중에 죽으면 빌드는 DEPLOYING 으로 남는다.
 * 다시 뜨면 POST 를 보내지 않고 lily-cicd 진행 상태만 따라 그 빌드를 닫는다.
 * 온프레미스 빌드는 에이전트가 보내는 단계를 다시 받도록 등록한다 ({@link AgentDeployService#resumeInFlight}).
 */
@Component
@ConditionalOnProperty(prefix = "lily.builder", name = "resume-deploys", matchIfMissing = true)
class DeployResume {

    private final BuildService builds;
    private final AgentDeployService agents;
    private final AppMigration migration;

    DeployResume(BuildService builds, AgentDeployService agents, AppMigration migration) {
        this.builds = builds;
        this.agents = agents;
        this.migration = migration;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        builds.resumeInterrupted();
        agents.resumeInFlight();
        // 옮기는 도중에 죽었으면 원본으로 되돌린다 (원본이 replicas 0 으로 남지 않게)
        migration.resumeInterrupted();
    }
}
