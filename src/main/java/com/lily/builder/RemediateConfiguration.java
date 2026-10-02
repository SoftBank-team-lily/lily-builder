package com.lily.builder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** {@code lily.remediate.enabled=true} 일 때만 diff 초안 빈을 만든다. 기본은 꺼져 있다. */
@Configuration
@ConditionalOnProperty(prefix = "lily.remediate", name = "enabled", havingValue = "true")
public class RemediateConfiguration {

    @Bean
    DefectGate defectGate(RemediateProperties properties) {
        return defectGateFor(properties.jevApiKey());
    }

    /**
     * jev 키가 없으면 초안은 막지 않는다. 아니오라고 답할 호출이 없기 때문이다.
     * PR 은 frontend 스위치와 프로젝트 동의가 막는다.
     */
    static DefectGate defectGateFor(String jevApiKey) {
        if (jevApiKey == null || jevApiKey.isBlank()) {
            return log -> java.util.Optional.of(true);
        }
        return new JevDefectGate(new com.lily.jev.HttpJev(jevApiKey, 0.8));
    }

    @Bean
    PatchModel patchModel() {
        return AiPatchModel.fromEnvironment();
    }

    @Bean
    RemediateDraftService remediateDraftService(GitHubSource github, DefectGate gate, PatchModel model) {
        return new RemediateDraftService((repoUrl, token, commit, path) -> github.file(
                new BuildRequest(repoUrl, "main", token, "", "app", null, "", null, null, java.util.Map.of()),
                commit, path), gate, model);
    }
}
