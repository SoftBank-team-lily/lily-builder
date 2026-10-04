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

    private static CloudRepository.Evidence evidence(String affinity, List<String> files) {
        return new CloudRepository.Evidence("a".repeat(40), "", files, Map.of("web", List.of("fastapi")),
                affinity, "rules", null, List.of());
    }
    private static final Jev MUST_NOT_ASK = new Jev() {
        public Optional<Answer> ask(Map<String, ?> state, com.lily.jev.Question question) { throw new AssertionError("JEV 를 부르면 안 된다"); }
    };

    @Test void singleCandidateIsSelectedWithoutAskingJev() {
        var result = CloudSelection.decide(evidence("unknown", List.of("requirements.txt")), java.util.Set.of("gcp"), MUST_NOT_ASK, .8);
        assertThat(result.status()).isEqualTo("selected");
        assertThat(result.provider()).isEqualTo("GCP");
        assertThat(result.reason()).isEqualTo("single_candidate");
        assertThat(result.confidence()).isNull();
    }

    @Test void portableRepositoryGoesToAwsWithoutAskingJev() {
        var result = CloudSelection.decide(evidence("portable", List.of("requirements.txt")), java.util.Set.of("aws", "gcp"), MUST_NOT_ASK, .8);
        assertThat(result.provider()).isEqualTo("AWS");
        assertThat(result.reason()).isEqualTo("portable_default");
    }

    @Test void holdFromJevFallsBackToDefaultInsteadOfBlockingTheFirstDeploy() {
        Jev holding = (state, question) -> Optional.of(new Answer("hold", null, 0.9));
        var result = CloudSelection.decide(evidence("unknown", List.of("requirements.txt")), java.util.Set.of("aws", "gcp"), holding, .8);
        assertThat(result.status()).isEqualTo("selected");
        assertThat(result.provider()).isEqualTo("AWS");
        assertThat(result.reason()).isEqualTo("fallback_default");
    }

    @Test void lowConfidenceOrNoAnswerFallsBackToDefault() {
        Jev unsure = (state, question) -> Optional.of(new Answer("gcp", null, 0.5));
        assertThat(CloudSelection.decide(evidence("unknown", List.of("pom.xml")), java.util.Set.of("aws", "gcp"), unsure, .8).provider()).isEqualTo("AWS");
        assertThat(CloudSelection.decide(evidence("unknown", List.of("pom.xml")), java.util.Set.of("aws", "gcp"), Jev.disabled(), .8).reason()).isEqualTo("fallback_default");
        Jev silent = (state, question) -> Optional.empty();
        assertThat(CloudSelection.decide(evidence("unknown", List.of("pom.xml")), java.util.Set.of("aws", "gcp"), silent, .8).provider()).isEqualTo("AWS");
    }

    @Test void repositoryLeaningToGcpKeepsGcpWhenJevCannotDecide() {
        var result = CloudSelection.decide(evidence("gcp", List.of("requirements.txt")), java.util.Set.of("aws", "gcp"), Jev.disabled(), .8);
        assertThat(result.provider()).isEqualTo("GCP");
        assertThat(result.reason()).isEqualTo("fallback_default");
    }

    @Test void confidentJevAnswerStillWins() {
        Jev confident = (state, question) -> Optional.of(new Answer("gcp", null, 0.92));
        var result = CloudSelection.decide(evidence("unknown", List.of("requirements.txt")), java.util.Set.of("aws", "gcp"), confident, .8);
        assertThat(result.provider()).isEqualTo("GCP");
        assertThat(result.reason()).isEqualTo("repository_jev");
        assertThat(result.confidence()).isEqualTo(0.92);
    }

    @Test void missingManifestOrWorkersStillHold() {
        assertThat(CloudSelection.decide(evidence("portable", List.of()), java.util.Set.of("aws"), MUST_NOT_ASK, .8).reason()).isEqualTo("repository_evidence_missing");
        assertThat(CloudSelection.decide(evidence("portable", List.of("pom.xml")), java.util.Set.of(), MUST_NOT_ASK, .8).reason()).isEqualTo("cloud_workers_unconfigured");
    }
}
