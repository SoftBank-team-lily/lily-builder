package com.lily.builder.cloud;

import com.lily.builder.BuilderProperties;
import com.lily.builder.CloudProfiles;
import com.lily.jev.Answer;
import com.lily.jev.Jev;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CloudSelectionTest {
    @Test void deployUrlsCountAsCandidatesWithoutCloudWorkerEnv() {
        var repository = mock(CloudRepository.class);
        var evidence = new CloudRepository.Evidence("a".repeat(40), "", List.of("pom.xml"), Map.of(),
                "unknown", "rules", null, List.of());
        when(repository.inspect(any())).thenReturn(evidence);
        var props = new CloudProperties("", "", "", 300, "", "", 0.8, 900,
                new CloudProperties.Worker("", "", ""), new CloudProperties.Worker("", "", ""));
        var builder = new BuilderProperties("lily-builds", "registry", false, "http://aws-cicd",
                "gcr.io/kaniko-project/executor:v1.23.2", 900,
                new BuilderProperties.Dynamodb("lily-builds", null, "ap-northeast-2", false), "");
        var gcp = new CloudProfiles("http://gcp-cicd", "asia-northeast3-docker.pkg.dev/p/lily", "gcp-pull",
                "", "", "", 80, "", "", "lily-tunnel", "", 5432, "");
        Jev jev = (state, question) -> Optional.of(new Answer("gcp", null, 0.91));
        var result = new CloudSelection(repository, props, jev, builder, gcp).choose(null);
        assertThat(result.status()).isEqualTo("selected");
        assertThat(result.provider()).isEqualTo("GCP");
    }

    @Test void selectionStaysOpenWhenCloudTokenIsUnset() throws Exception {
        var selection = mock(CloudSelection.class);
        var evidence = new CloudRepository.Evidence("a".repeat(40), "", List.of("pom.xml"), Map.of(),
                "unknown", "rules", null, List.of());
        when(selection.choose(any())).thenReturn(new CloudSelection.Result("selected", "GCP", "repository_jev", 0.91, evidence));
        var props = new CloudProperties("", "", "", 300, "", "", 0.8, 900,
                new CloudProperties.Worker("", "", ""), new CloudProperties.Worker("", "", ""));
        var mvc = MockMvcBuilders.standaloneSetup(new CloudSelectionController(selection))
                .addFilters(new CloudTokenFilter(props)).build();
        mvc.perform(post("/api/cloud/selection").servletPath("/api/cloud/selection")
                        .contentType("application/json")
                        .content("{\"repoUrl\":\"https://github.com/owner/sample\",\"appName\":\"sample\",\"deploymentMode\":\"HYBRID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("GCP"));
        mvc = MockMvcBuilders.standaloneSetup(new CloudController(
                        mock(CloudService.class), mock(CloudWorkers.class), mock(CloudRepository.class),
                        mock(CloudDispatches.class), mock(CloudPlans.class)))
                .addFilters(new CloudTokenFilter(props)).build();
        mvc.perform(post("/api/cloud/plans").servletPath("/api/cloud/plans")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("cloud_disabled"));
    }
}
