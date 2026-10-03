package com.lily.builder.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lily.jev.Jev;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CloudApiTest {
    static final String TOKEN = "test-only-token-012345678901234567890123";
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static CloudRepository repository() {
        var repository = mock(CloudRepository.class);
        when(repository.inspect(any())).thenReturn(new CloudRepository.Evidence("a".repeat(40),"",List.of("package.json"),
            java.util.Map.of("web",List.of("next")),"portable","rules",null,List.of()));
        return repository;
    }
    static CloudController controller(CloudService service, CloudWorkers workers, CloudRepository repository) {
        var state=new MemoryCloudState();
        var plans=new CloudPlans(state,props("","http://worker"),JSON,Clock.fixed(CloudPolicyTest.NOW,ZoneOffset.UTC));
        return new CloudController(service,workers,repository,new CloudDispatches(state,workers,service,plans),plans);
    }
    static void approve(org.springframework.test.web.servlet.MockMvc mvc, com.fasterxml.jackson.databind.node.ObjectNode body) throws Exception {
        var response=mvc.perform(post("/api/cloud/repository").contentType("application/json").content(body.toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        body.put("planId",JSON.readTree(response).path("planId").asText());
        body.put("requestId",java.util.UUID.randomUUID().toString());
    }
    static CloudProperties props(String catalog, String worker) {
        return new CloudProperties(TOKEN,catalog,"",300,"","",0.8,900,new CloudProperties.Worker(worker,"","ap-northeast-2"),
            new CloudProperties.Worker(worker,"","asia-northeast3"));
    }
    @Test void previewAndDeployReachTheSameDecisionForTheSameEvidence() {
        var catalog = mock(CloudCatalog.class);
        // 같은 근거를 다시 수집해 관측 시각만 바뀐다
        var refreshed = CloudPolicyTest.candidates().stream().map(c -> new CloudPolicy.Candidate(c.provider(),c.region(),c.profile(),
            c.monthlyCostUsd(),c.p95Ms(),c.available(),c.capabilities(),c.observedAt().minusSeconds(60),c.evidenceId())).toList();
        when(catalog.read()).thenReturn(CloudPolicyTest.candidates(), refreshed);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        // 부를 때마다 다른 답을 내는 모델
        com.lily.jev.Jev model = (state,question) -> java.util.Optional.of(
            new com.lily.jev.Answer(calls.incrementAndGet() == 1 ? "gcp" : "aws", null, .95));
        var jev = new com.lily.jev.CachedJev(model, java.time.Duration.ofMinutes(15), 16);
        var service = new CloudService(catalog, props("", "http://worker"), new CloudPolicy(jev),
            Clock.fixed(CloudPolicyTest.NOW, ZoneOffset.UTC));
        var preview = service.plan(CloudPolicyTest.request("balanced"));
        var deploy = service.plan(CloudPolicyTest.request("balanced"));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(deploy.provider()).isEqualTo(preview.provider()).isEqualTo("gcp");
    }
    @Test void cloudJevFollowsConfiguredConfidenceAndKey() {
        var config = new CloudConfiguration();
        assertThat(config.cloudJev(props("","")).available()).isFalse();
        var keyed = new CloudProperties(TOKEN,"","",300,"k".repeat(40),"",0.8,900,null,null);
        assertThat(config.cloudJev(keyed)).isInstanceOf(com.lily.jev.CachedJev.class);
        var invalid = new CloudProperties(TOKEN,"","",300,"k".repeat(40),"",1.5,900,null,null);
        assertThatThrownBy(() -> config.cloudJev(invalid)).isInstanceOf(IllegalStateException.class);
    }
    @Test void authenticatesAndValidatesBeforeCallingModel() throws Exception {
        var service = mock(CloudService.class);
        var workers = mock(CloudWorkers.class);
        var mvc = MockMvcBuilders.standaloneSetup(controller(service,workers,repository()))
            .addFilters(new CloudTokenFilter(props("", ""))).build();
        mvc.perform(post("/api/cloud/plans").servletPath("/api/cloud/plans").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/cloud/plans").servletPath("/api/cloud/plans").header("Authorization","Bearer " + TOKEN)
            .contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/cloud/plans").servletPath("/api/cloud/plans").header("Authorization","Bearer " + TOKEN)
            .contentType("application/json").content("x".repeat(262145)))
            .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(service,workers);
    }
    @Test void latestEvidenceCanCancelDispatch() {
        var catalog = mock(CloudCatalog.class);
        when(catalog.read()).thenReturn(CloudPolicyTest.candidates(), List.of());
        var service = new CloudService(catalog, props("", "http://worker"), new CloudPolicy(Jev.disabled()),
            Clock.fixed(CloudPolicyTest.NOW, ZoneOffset.UTC));
        var r = CloudPolicyTest.request("cost");
        var selected = service.plan(r);
        assertThat(selected.provider()).isEqualTo("gcp");
        assertThat(service.recheck(r,selected).reason()).isEqualTo("changed_before_dispatch");
    }
    @Test void repositoryFailureNeverStartsAWorker() throws Exception {
        var service = mock(CloudService.class);
        var workers = mock(CloudWorkers.class);
        var repository = mock(CloudRepository.class);
        when(repository.inspect(any())).thenThrow(new CloudRepository.Unavailable());
        var mvc = MockMvcBuilders.standaloneSetup(controller(service,workers,repository)).build();
        var body = JSON.createObjectNode();
        body.set("policy",JSON.valueToTree(CloudPolicyTest.request("balanced")));
        body.set("build",JSON.readTree("{\"repoUrl\":\"https://github.com/owner/sample\",\"appName\":\"sample\"}"));
        mvc.perform(post("/api/cloud/repository").contentType("application/json").content(body.toString()))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("repository_analysis_unavailable"));
        // 배포할 폴더를 정하지 못하면 화면이 폴더를 고르게 할 수 있도록 이유를 따로 준다
        reset(repository);
        when(repository.inspect(any())).thenThrow(new CloudRepository.Unavailable("build_folder_unresolved"));
        mvc.perform(post("/api/cloud/repository").contentType("application/json").content(body.toString()))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("build_folder_unresolved"));
        verifyNoInteractions(service,workers);
    }
    @Test void controllerPreservesContextAndValidatesItsFields() throws Exception {
        var service = mock(CloudService.class);
        var workers = mock(CloudWorkers.class);
        when(service.plan(any(),anyMap())).thenReturn(CloudPolicy.held("no_eligible_cloud",List.of(),List.of(),CloudPolicyTest.NOW));
        var mvc = MockMvcBuilders.standaloneSetup(controller(service,workers,repository())).build();
        var body = JSON.createObjectNode();
        var policy = JSON.valueToTree(CloudPolicyTest.request("balanced"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)policy).set("context",JSON.readTree("{\"dataProvider\":\"gcp\",\"keepDataLocal\":true}"));
        body.set("policy",policy);
        body.set("build",JSON.readTree("{\"repoUrl\":\"https://github.com/owner/sample\",\"appName\":\"sample\"}"));
        mvc.perform(post("/api/cloud/repository").contentType("application/json").content(body.toString())).andExpect(status().isOk());
        verify(service).plan(argThat(r -> r.context() != null && "gcp".equals(r.context().dataProvider())
            && Boolean.TRUE.equals(r.context().keepDataLocal())),anyMap());
        ((com.fasterxml.jackson.databind.node.ObjectNode)policy.path("context")).put("dataProvider","unrecognized");
        mvc.perform(post("/api/cloud/repository").contentType("application/json").content(body.toString())).andExpect(status().isBadRequest());
        verifyNoInteractions(workers);
    }
    @Test void readsCatalogAndRoutesSelectedGcpBuildThenPollsSameProvider() throws Exception {
        try (MockWebServer catalog = new MockWebServer(); MockWebServer worker = new MockWebServer()) {
            catalog.start(); worker.start();
            var snapshot = JSON.writeValueAsString(java.util.Map.of("candidates",CloudPolicyTest.candidates()));
            catalog.enqueue(new MockResponse().setBody(snapshot));
            catalog.enqueue(new MockResponse().setBody(snapshot));
            worker.enqueue(new MockResponse().setResponseCode(202).setBody("{\"id\":\"abc123\",\"status\":\"QUEUED\",\"token\":\"hidden\"}"));
            worker.enqueue(new MockResponse().setBody("{\"id\":\"abc123\",\"status\":\"SUCCEEDED\"}"));
            var p = props(catalog.url("/snapshot").toString(),worker.url("/").toString());
            var service = new CloudService(new CloudCatalog(p,JSON),p,new CloudPolicy(Jev.disabled()),Clock.fixed(CloudPolicyTest.NOW,ZoneOffset.UTC));
            var mvc = MockMvcBuilders.standaloneSetup(controller(service,new CloudWorkers(p,JSON),repository())).build();
            var body = JSON.createObjectNode();
            body.set("policy",JSON.valueToTree(CloudPolicyTest.request("cost")));
            body.set("build",JSON.readTree("{\"repoUrl\":\"https://github.com/owner/sample\",\"appName\":\"sample\",\"database\":\"postgres\"}"));
            approve(mvc,body);
            mvc.perform(post("/api/cloud/builds").contentType("application/json").content(body.toString()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.decision.provider").value("gcp"))
                .andExpect(jsonPath("$.statusPath").value("/api/cloud/builds/gcp/abc123"))
                .andExpect(jsonPath("$.build.token").doesNotExist());
            var posted = worker.takeRequest();
            assertThat(posted.getPath()).isEqualTo("/api/builds");
            assertThat(JSON.readTree(posted.getBody().readUtf8()).path("sourceCommit").asText()).isEqualTo("a".repeat(40));
            mvc.perform(get("/api/cloud/builds/gcp/abc123")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
            assertThat(worker.takeRequest().getPath()).isEqualTo("/api/builds/abc123");
        }
    }
    @Test void rejectsFailedWorkerWithoutRetryOrOtherCloud() throws Exception {
        try (MockWebServer worker = new MockWebServer()) {
            worker.start(); worker.enqueue(new MockResponse().setResponseCode(500).setBody("secret detail"));
            var workers = new CloudWorkers(props("",worker.url("/").toString()),JSON);
            var request = JSON.readValue("{\"repoUrl\":\"https://github.com/owner/sample\",\"appName\":\"sample\"}",com.lily.builder.BuildRequest.class);
            assertThatThrownBy(() -> workers.start("gcp",request)).hasMessage("dispatch_unconfirmed");
            assertThat(worker.getRequestCount()).isEqualTo(1);
        }
    }
    @Test void secondDispatchForTheSameAppReturnsTheFirstBuild() throws Exception {
        try (MockWebServer catalog = new MockWebServer(); MockWebServer worker = new MockWebServer()) {
            catalog.start(); worker.start();
            var snapshot = JSON.writeValueAsString(java.util.Map.of("candidates",CloudPolicyTest.candidates()));
            for (int i = 0; i < 4; i++) catalog.enqueue(new MockResponse().setBody(snapshot));
            worker.enqueue(new MockResponse().setResponseCode(202).setBody("{\"id\":\"abc123\",\"status\":\"QUEUED\"}"));
            worker.enqueue(new MockResponse().setBody("{\"id\":\"abc123\",\"status\":\"QUEUED\"}"));
            var p = props(catalog.url("/snapshot").toString(),worker.url("/").toString());
            var service = new CloudService(new CloudCatalog(p,JSON),p,new CloudPolicy(Jev.disabled()),Clock.fixed(CloudPolicyTest.NOW,ZoneOffset.UTC));
            var mvc = MockMvcBuilders.standaloneSetup(controller(service,new CloudWorkers(p,JSON),repository())).build();
            var body = JSON.createObjectNode();
            body.set("policy",JSON.valueToTree(CloudPolicyTest.request("cost")));
            body.set("build",JSON.readTree("{\"repoUrl\":\"https://github.com/owner/sample\",\"appName\":\"sample\",\"database\":\"postgres\"}"));
            approve(mvc,body);
            mvc.perform(post("/api/cloud/builds").contentType("application/json").content(body.toString())).andExpect(status().isAccepted());
            mvc.perform(post("/api/cloud/builds").contentType("application/json").content(body.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.buildId").value("abc123"))
                .andExpect(jsonPath("$.statusPath").value("/api/cloud/builds/gcp/abc123"));
            assertThat(worker.getRequestCount()).isEqualTo(2);
        }
    }
    @Test void onlyARejectedDispatchCanBeSentAgain() throws Exception {
        try (MockWebServer worker = new MockWebServer()) {
            worker.start(); worker.enqueue(new MockResponse().setResponseCode(400));
            var workers = new CloudWorkers(props("",worker.url("/").toString()),JSON);
            var request = JSON.readValue("{\"repoUrl\":\"https://github.com/owner/sample\",\"appName\":\"sample\"}",com.lily.builder.BuildRequest.class);
            assertThatThrownBy(() -> workers.start("gcp",request)).hasMessage("dispatch_rejected");
        }
    }
    @Test void heldPlanNeverStartsAWorker() throws Exception {
        var service = mock(CloudService.class);
        var workers = mock(CloudWorkers.class);
        when(service.plan(any(),anyMap())).thenReturn(CloudPolicy.held("no_eligible_cloud",List.of(),List.of(),CloudPolicyTest.NOW));
        var mvc = MockMvcBuilders.standaloneSetup(controller(service,workers,repository())).build();
        var body = JSON.createObjectNode();
        body.set("policy",JSON.valueToTree(CloudPolicyTest.request("cost")));
        body.set("build",JSON.readTree("{\"repoUrl\":\"https://github.com/owner/sample\",\"appName\":\"sample\"}"));
        mvc.perform(post("/api/cloud/repository").contentType("application/json").content(body.toString())).andExpect(status().isOk());
        verifyNoInteractions(workers);
    }
    @Test void redirectsNeverForwardWorkerTokens() throws Exception {
        try (MockWebServer worker = new MockWebServer(); MockWebServer destination = new MockWebServer()) {
            worker.start(); destination.start();
            worker.enqueue(new MockResponse().setResponseCode(302).setHeader("Location",destination.url("/other")));
            var workers = new CloudWorkers(props("",worker.url("/").toString()),JSON);
            assertThatThrownBy(() -> workers.get("gcp","abc123")).hasMessage("worker_unavailable");
            assertThat(destination.getRequestCount()).isZero();
        }
    }
    @Test void catalogOutagesAreReportedApartFromNoCandidates() throws Exception {
        try (MockWebServer catalog = new MockWebServer()) {
            catalog.start(); catalog.enqueue(new MockResponse().setResponseCode(503).setBody("secret detail"));
            catalog.enqueue(new MockResponse().setBody("{broken"));
            catalog.enqueue(new MockResponse().setBody("{\"candidates\":[]}"));
            var source = new CloudCatalog(props(catalog.url("/").toString(),""),JSON);
            assertThatThrownBy(source::read).hasMessage("catalog_unavailable");
            assertThatThrownBy(source::read).hasMessage("catalog_unavailable");
            assertThat(source.read()).isEmpty();
            assertThatThrownBy(() -> new CloudCatalog(props("",""),JSON).read()).hasMessage("catalog_unconfigured");
        }
    }
    @Test void plansAndRechecksNameTheCatalogOutage() {
        var catalog = mock(CloudCatalog.class);
        var service = new CloudService(catalog, props("", "http://worker"), new CloudPolicy(Jev.disabled()),
            Clock.fixed(CloudPolicyTest.NOW, ZoneOffset.UTC));
        var r = CloudPolicyTest.request("cost");
        when(catalog.read()).thenThrow(new CloudCatalog.Unavailable("catalog_unconfigured"));
        assertThat(service.plan(r).reason()).isEqualTo("catalog_unconfigured");
        reset(catalog);
        when(catalog.read()).thenReturn(CloudPolicyTest.candidates()).thenThrow(new CloudCatalog.Unavailable("catalog_unavailable"));
        var selected = service.plan(r);
        assertThat(selected.status()).isEqualTo("selected");
        assertThat(service.recheck(r, selected).reason()).isEqualTo("catalog_unavailable");
    }
}
