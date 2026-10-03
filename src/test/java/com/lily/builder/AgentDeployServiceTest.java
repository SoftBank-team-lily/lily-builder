package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 온프레미스 배포: 잡 전송과 에이전트 상태 → Build */
class AgentDeployServiceTest {

    private static final String KEY = "a1b2c3d4e5f6";
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    private final InMemoryBuildStore store = new InMemoryBuildStore();
    private final GitHubSource github = mock(GitHubSource.class);
    private final BuildService builds = mock(BuildService.class);
    private final AgentHub hub = mock(AgentHub.class);
    private final ProvisionerClient provisioner = mock(ProvisionerClient.class);
    private final BuildService.BuildRunner runner = new BuildServiceTest.SyncRunner();
    private final AgentDeployService service = new AgentDeployService(store, github, builds, hub, runner,
            new BuilderProperties("ns", "reg", false, "http://cicd", "kaniko", 900,
                    new BuilderProperties.Dynamodb("t", null, "ap-northeast-2", false), ""),
            provisioner);

    private static BuildRequest request(String appName) {
        return new BuildRequest("https://github.com/org/blog", null, null, null, appName, null, "auto", null, null,
                Map.of("A", "1"));
    }

    @BeforeEach
    void setUp() {
        when(hub.connected(KEY)).thenReturn(true);
        when(hub.agentId(KEY)).thenReturn("edge-1");
        when(hub.supportsDatabase(KEY)).thenReturn(true);
        when(github.resolveCommit(any())).thenReturn(COMMIT);
        when(builds.source(any(), any(), eq(COMMIT))).thenAnswer(call -> new BuildService.Source(call.getArgument(1), null, null));
        // 레포 감지 결과: 포트 3000, postgres, actuator 없음
        when(builds.detect(any(), any(), eq(COMMIT), any(), any())).thenAnswer(call -> {
            BuildRequest r = call.getArgument(1);
            return r.withDetected(3000, "postgres", "/", "/");
        });
    }

    @Test
    void 레포를_보고_정한_값으로_잡을_보낸다() throws Exception {
        Build build = service.start(KEY, request("blog-1b62c0"));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(hub).send(eq(KEY), sent.capture());
        JsonNode job = new ObjectMapper().readTree(sent.getValue());
        assertThat(job.path("type").asText()).isEqualTo("job");
        assertThat(job.path("id").asText()).isEqualTo(build.getId());
        assertThat(job.path("appName").asText()).isEqualTo("blog-1b62c0");
        assertThat(job.path("branch").asText()).isEqualTo("main");
        assertThat(job.path("targetPort").asInt()).isEqualTo(3000);
        assertThat(job.path("healthPath").asText()).isEqualTo("/");
        assertThat(job.path("database").asText()).isEqualTo("postgres");
        assertThat(job.path("env").path("A").asText()).isEqualTo("1");
        assertThat(build.getLogs()).contains("agent: send to edge-1");
        // 레포에 Dockerfile 이 있으면 보내지 않는다 (에이전트가 레포 것을 쓴다)
        assertThat(job.has("dockerfile")).isFalse();
    }

    @Test
    void 레포에_Dockerfile_이_없으면_클라우드와_같이_만든_Dockerfile_을_보낸다() throws Exception {
        when(builds.source(any(), any(), eq(COMMIT))).thenAnswer(call ->
                new BuildService.Source(call.getArgument(1), "FROM node:22-slim", null));

        service.start(KEY, request("web-1b62c0"));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(hub).send(eq(KEY), sent.capture());
        assertThat(new ObjectMapper().readTree(sent.getValue()).path("dockerfile").asText())
                .isEqualTo("FROM node:22-slim");
        verify(builds).detect(any(), any(), eq(COMMIT), eq("FROM node:22-slim"), any());
    }

    @Test
    void DB_가_필요한데_에이전트에_DB_터널이_없으면_보내지_않고_실패() {
        when(hub.supportsDatabase(KEY)).thenReturn(false);

        Build build = service.start(KEY, request("blog"));

        assertThat(build.getStatus()).isEqualTo(Build.Status.FAILED);
        assertThat(build.getLogs().get(build.getLogs().size() - 1))
                .contains("DB(postgres)가 필요한데").contains("DB 터널이 없다");
        verify(hub, never()).send(anyString(), anyString());
    }

    @Test
    void DB_가_필요_없는_앱은_DB_터널이_없어도_보낸다() throws Exception {
        when(hub.supportsDatabase(KEY)).thenReturn(false);
        when(builds.detect(any(), any(), eq(COMMIT), any(), any())).thenAnswer(call -> {
            BuildRequest r = call.getArgument(1);
            return r.withDetected(3000, null, "/", "/");
        });

        Build build = service.start(KEY, request("web"));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(hub).send(eq(KEY), sent.capture());
        assertThat(new ObjectMapper().readTree(sent.getValue()).path("database").isNull()).isTrue();
        assertThat(build.getStatus()).isNotEqualTo(Build.Status.FAILED);
    }

    @Test
    void 에이전트가_연결돼_있지_않으면_바로_실패() {
        when(hub.connected(KEY)).thenReturn(false);

        Build build = service.start(KEY, request("blog"));

        assertThat(build.getStatus()).isEqualTo(Build.Status.FAILED);
        assertThat(build.getLogs().get(build.getLogs().size() - 1)).contains("에이전트가 연결돼 있지 않다");
        verify(hub, never()).send(anyString(), anyString());
    }

    @Test
    void 에이전트_단계를_Build_상태와_진행_단계로_바꾼다() {
        Build build = service.start(KEY, request("blog"));
        String id = build.getId();

        service.agentStatus(KEY, id, "CHECKOUT", "checkout: main", "");
        assertThat(build.getStatus()).isEqualTo(Build.Status.QUEUED);
        service.agentStatus(KEY, id, "BUILDING", "build: docker build", "");
        assertThat(build.getStatus()).isEqualTo(Build.Status.BUILDING);
        assertThat(build.getStage()).isEqualTo(1);
        service.agentStatus(KEY, id, "HEALTH", "health: waiting", "");
        assertThat(build.getStatus()).isEqualTo(Build.Status.DEPLOYING);
        assertThat(build.getStage()).isEqualTo(2);
        service.agentStatus(KEY, id, "JUDGING", "judge: candidate", "");
        assertThat(build.getStage()).isEqualTo(4);
        service.agentStatus(KEY, id, "SUCCEEDED", "done: https://blog.lilycloud.kr", "https://blog.lilycloud.kr");

        assertThat(build.getStatus()).isEqualTo(Build.Status.SUCCEEDED);
        assertThat(build.getUrl()).isEqualTo("https://blog.lilycloud.kr");
        assertThat(build.getStage()).isEqualTo(5);
        assertThat(store.find(id)).contains(build);
    }

    @Test
    void 에이전트가_실패를_보내면_FAILED() {
        Build build = service.start(KEY, request("blog"));

        service.agentStatus(KEY, build.getId(), "FAILED", "health: timeout", "");

        assertThat(build.getStatus()).isEqualTo(Build.Status.FAILED);
        assertThat(build.getLogs()).contains("agent: FAILED health: timeout");
    }

    @Test
    void 다른_에이전트가_보낸_상태는_무시한다() {
        Build build = service.start(KEY, request("blog"));

        service.agentStatus("ffffffffffff", build.getId(), "SUCCEEDED", "done", "https://evil");

        assertThat(build.getStatus()).isNotEqualTo(Build.Status.SUCCEEDED);
        assertThat(build.getUrl()).isNull();
    }

    @Test
    void 잡을_받은_에이전트가_다른_agentId로_hello를_보내면_제한_시간을_기다리지_않고_FAILED() {
        Build build = service.start(KEY, request("blog"));
        service.agentStatus(KEY, build.getId(), "BUILDING", "build: docker build", "");

        service.agentHello(KEY, "edge-2");

        assertThat(build.getStatus()).isEqualTo(Build.Status.FAILED);
        assertThat(build.getLogs().get(build.getLogs().size() - 1))
                .contains("에이전트가 다시 시작돼 잡이 사라졌다 (edge-1 → edge-2)");
    }

    @Test
    void 잡을_받은_에이전트가_같은_agentId로_다시_hello를_보내면_빌드를_그대로_두고_이후_단계를_받는다() {
        Build build = service.start(KEY, request("blog"));
        service.agentStatus(KEY, build.getId(), "BUILDING", "build: docker build", "");

        service.agentHello(KEY, "edge-1");
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.lilycloud.kr");

        assertThat(build.getStatus()).isEqualTo(Build.Status.SUCCEEDED);
    }

    @Test
    void 다른_key의_에이전트가_hello를_보내면_이_key로_보낸_빌드는_그대로_둔다() {
        Build build = service.start(KEY, request("blog"));
        service.agentStatus(KEY, build.getId(), "BUILDING", "build: docker build", "");

        service.agentHello("ffffffffffff", "edge-9");

        assertThat(build.getStatus()).isEqualTo(Build.Status.BUILDING);
    }

    @Test
    void 에이전트_앱_이름_규칙에_맞지_않으면_거절한다() {
        assertThatThrownBy(() -> service.start(KEY, request("1blog")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.start(KEY, request("a".repeat(32))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 최근_성공이_온프레미스면_그_에이전트에_롤백을_보낸다() throws Exception {
        Build build = service.start(KEY, request("blog-1b62c0"));
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");
        doAnswer(invocation -> {
            JsonNode body = new ObjectMapper().readTree(invocation.getArgument(1, String.class));
            if ("rollback".equals(body.path("type").asText())) {
                service.agentStatus(KEY, body.path("id").asText(), "SUCCEEDED", "rollback: slot=blue", "");
            }
            return null;
        }).when(hub).send(eq(KEY), anyString());

        String response = service.rollback("blog-1b62c0").orElseThrow();

        assertThat(response).contains("ROLLED_BACK").contains("unchanged").contains("slot=blue");
    }

    @Test
    void 에이전트가_pgroll_롤백_창_안에서_스키마까지_되돌리면_schema_reverted로_알린다() throws Exception {
        Build build = service.start(KEY, request("blog-1b62c0"));
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");
        doAnswer(invocation -> {
            JsonNode body = new ObjectMapper().readTree(invocation.getArgument(1, String.class));
            if ("rollback".equals(body.path("type").asText())) {
                service.agentStatus(KEY, body.path("id").asText(), "SUCCEEDED", "rollback: slot=blue schema=reverted", "");
            }
            return null;
        }).when(hub).send(eq(KEY), anyString());

        String response = service.rollback("blog-1b62c0").orElseThrow();

        assertThat(new ObjectMapper().readTree(response).path("schema").asText()).isEqualTo("reverted");
    }

    @Test
    void PostgreSQL_앱의_pgroll_파일은_pgroll을_받는_에이전트에만_보낸다() throws Exception {
        when(github.migrations(any(), eq(COMMIT))).thenReturn(Map.of("01_create_posts.yaml", "operations: []"));
        when(hub.supports(KEY, "pgroll")).thenReturn(false);

        Build rejected = service.start(KEY, request("blog-1b62c0"));

        assertThat(rejected.getStatus()).isEqualTo(Build.Status.FAILED);
        assertThat(rejected.getLogs()).anyMatch(line -> line.contains("pgroll 마이그레이션을 받지 못하는 판"));
        verify(hub, never()).send(anyString(), anyString());

        when(hub.supports(KEY, "pgroll")).thenReturn(true);
        service.start(KEY, request("blog-2c73d1"));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(hub).send(eq(KEY), sent.capture());
        JsonNode job = new ObjectMapper().readTree(sent.getValue());
        assertThat(job.path("migrations").has("01_create_posts.yaml")).isTrue();
    }

    @Test
    void 온프레미스_앱의_스키마_이력은_에이전트가_보낸_상태를_그대로_돌려주고_complete는_에이전트에_보낸다() throws Exception {
        Build build = service.start(KEY, request("blog-1b62c0"));
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");
        when(hub.lastState(KEY)).thenReturn(new ObjectMapper().readTree("""
                {"type":"burst-state","app":"blog-1b62c0","schema":{"appName":"blog-1b62c0","engine":"pgroll",
                 "currentVersion":"02_add_slug","window":{"migration":"02_add_slug"},"history":[]}}"""));

        JsonNode schema = new ObjectMapper().readTree(service.schema("blog-1b62c0").orElseThrow());
        service.completeSchema("blog-1b62c0").orElseThrow();

        assertThat(schema.path("engine").asText()).isEqualTo("pgroll");
        assertThat(schema.path("window").path("migration").asText()).isEqualTo("02_add_slug");
        verify(hub).send(KEY, "{\"type\":\"schema-complete\",\"app\":\"blog-1b62c0\"}");
        assertThat(service.schema("cloud-only-app")).isEmpty();
    }

    @Test
    void 온프레미스_앱을_지우면_그_에이전트에_database_옵션과_함께_remove를_보내고_removed를_돌려준다() throws Exception {
        Build build = service.start(KEY, request("blog-1b62c0"));
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");
        when(hub.supports(KEY, "remove")).thenReturn(true);
        java.util.List<JsonNode> sent = new java.util.ArrayList<>();
        doAnswer(invocation -> {
            JsonNode body = new ObjectMapper().readTree(invocation.getArgument(1, String.class));
            if ("remove".equals(body.path("type").asText())) {
                sent.add(body);
                service.agentStatus(KEY, body.path("id").asText(), "SUCCEEDED", "removed: containers", "");
            }
            return null;
        }).when(hub).send(eq(KEY), anyString());

        AgentDeployService.AgentRemoval removal = service.remove("blog-1b62c0", true).orElseThrow();

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).path("app").asText()).isEqualTo("blog-1b62c0");
        assertThat(sent.get(0).path("database").asBoolean()).isTrue();
        assertThat(removal.status()).isEqualTo("removed");
        assertThat(removal.agent()).isEqualTo(KEY);
    }

    @Test
    void 온프레미스_앱을_지울_때_에이전트가_꺼져_있으면_보내지_않고_offline을_돌려준다() {
        Build build = service.start(KEY, request("blog-1b62c0"));
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");
        when(hub.connected(KEY)).thenReturn(false);

        AgentDeployService.AgentRemoval removal = service.remove("blog-1b62c0", false).orElseThrow();

        assertThat(removal.status()).isEqualTo("offline");
        verify(hub, never()).send(eq(KEY), org.mockito.ArgumentMatchers.contains("\"type\":\"remove\""));
    }

    @Test
    void 온프레미스_앱_삭제를_에이전트가_거절하면_그_이유로_실패한다() throws Exception {
        Build build = service.start(KEY, request("blog-1b62c0"));
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");
        when(hub.supports(KEY, "remove")).thenReturn(true);
        doAnswer(invocation -> {
            JsonNode body = new ObjectMapper().readTree(invocation.getArgument(1, String.class));
            if ("remove".equals(body.path("type").asText())) {
                service.agentStatus(KEY, body.path("id").asText(), "FAILED", "failed: 거점을 옮기는 중이라 지울 수 없습니다", "");
            }
            return null;
        }).when(hub).send(eq(KEY), anyString());

        assertThatThrownBy(() -> service.remove("blog-1b62c0", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("거점을 옮기는 중");
    }

    @Test
    void 에이전트_빌드가_없는_앱은_지우기를_보내지_않고_empty를_돌려준다() {
        assertThat(service.remove("cloud-app", true)).isEmpty();
        verify(hub, never()).send(anyString(), anyString());
    }

    @Test
    void 최근_성공이_온프레미스면_그_에이전트에_거점_전환을_보낸다() throws Exception {
        Build build = service.start(KEY, request("blog-1b62c0"));
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");
        doAnswer(invocation -> {
            JsonNode body = new ObjectMapper().readTree(invocation.getArgument(1, String.class));
            if ("home".equals(body.path("type").asText())) {
                service.agentStatus(KEY, body.path("id").asText(), "SUCCEEDED", "home: CLOUD", "");
            }
            return null;
        }).when(hub).send(eq(KEY), anyString());

        String response = service.home("blog-1b62c0", "cloud").orElseThrow();

        assertThat(response).contains("MOVED").contains("cloud").contains("CLOUD");
    }

    @Test
    void 거점_전환에_DB_이전_옵션을_싣는다() throws Exception {
        Build build = service.start(KEY, request("blog-1b62c0"));
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");
        java.util.List<JsonNode> sent = new java.util.ArrayList<>();
        doAnswer(invocation -> {
            JsonNode body = new ObjectMapper().readTree(invocation.getArgument(1, String.class));
            if ("home".equals(body.path("type").asText())) {
                sent.add(body);
                service.agentStatus(KEY, body.path("id").asText(), "SUCCEEDED", "home: CLOUD", "");
            }
            return null;
        }).when(hub).send(eq(KEY), anyString());

        service.home("blog-1b62c0", "cloud", true).orElseThrow();

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).path("migrateDatabase").asBoolean()).isTrue();
    }

    private static BuildRequest importing(String appName, String database) {
        return new BuildRequest("https://github.com/org/blog", null, null, null, appName, 8080, database, "/", "/",
                Map.of(), null, null, null, null, null, "local", null, null, true);
    }

    @Test
    void 온프레미스_전용은_RDS_프로비저닝과_거점_전환을_호출하지_않는다() throws Exception {
        when(hub.supportsDatabaseMode(KEY, "local")).thenReturn(true);
        when(hub.platformDatabase(KEY)).thenReturn(true);
        when(hub.tunnel(KEY)).thenReturn(new AgentHub.Tunnel("172.17.0.1", 15432));

        Build build = service.start(KEY, new BuildRequest(
                "https://github.com/org/blog", null, null, null, "blog-1b62c0", 8080, "postgres", "/", "/",
                Map.of(), null, null, null, null, null, "cloud", null, null, null, "ONPREM_ONLY"));
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");

        verify(provisioner, never()).ensure(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(hub).send(eq(KEY), sent.capture());
        JsonNode job = new ObjectMapper().readTree(sent.getValue());
        assertThat(job.path("deploymentMode").asText()).isEqualTo("ONPREM_ONLY");
        assertThat(job.path("databaseMode").asText()).isEqualTo("local");
        assertThat(job.path("databaseEnv").isMissingNode()).isTrue();

        assertThatThrownBy(() -> service.home("blog-1b62c0", "cloud"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("온프레미스 전용");
        verify(hub).send(eq(KEY), anyString());
    }

    @Test
    void 클라우드_앱을_옮기면_RDS_접속_정보와_import_를_잡에_싣는다() throws Exception {
        when(hub.supportsDatabaseMode(KEY, "local")).thenReturn(true);
        when(hub.supportsDatabaseMode(KEY, "import")).thenReturn(true);
        when(hub.platformDatabase(KEY)).thenReturn(true);
        when(hub.tunnel(KEY)).thenReturn(new AgentHub.Tunnel("172.17.0.1", 15432));
        when(provisioner.ensure("blog-1b62c0", "postgres", "172.17.0.1", 15432))
                .thenReturn(new ProvisionerClient.Connection("db1",
                        Map.of("DATABASE_URL", "postgresql://u:p@172.17.0.1:15432/blog")));

        service.start(KEY, importing("blog-1b62c0", "postgres"));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(hub).send(eq(KEY), sent.capture());
        JsonNode job = new ObjectMapper().readTree(sent.getValue());
        assertThat(job.path("databaseMode").asText()).isEqualTo("local");
        assertThat(job.path("importDatabase").asBoolean()).isTrue();
        assertThat(job.path("databaseEnv").path("DATABASE_URL").asText()).contains("172.17.0.1:15432");
    }

    @Test
    void 옮기기를_모르는_에이전트에는_보내지_않는다() {
        when(hub.supportsDatabaseMode(KEY, "local")).thenReturn(true);
        when(hub.platformDatabase(KEY)).thenReturn(true);

        Build build = service.start(KEY, importing("blog-1b62c0", "postgres"));

        assertThat(store.find(build.getId()).orElseThrow().getStatus()).isEqualTo(Build.Status.FAILED);
        verify(hub, never()).send(eq(KEY), anyString());
    }

    /** builder 가 다시 뜬 뒤: 메모리는 비고 빌드 기록만 남는다 */
    private AgentDeployService restarted(int timeoutSeconds) {
        return new AgentDeployService(store, github, builds, hub, runner,
                new BuilderProperties("ns", "reg", false, "http://cicd", "kaniko", timeoutSeconds,
                        new BuilderProperties.Dynamodb("t", null, "ap-northeast-2", false), ""),
                provisioner);
    }

    @Test
    void 재시작한_뒤에도_에이전트가_보낸_단계와_결과를_받는다() {
        Build build = service.start(KEY, request("blog-1b62c0"));
        AgentDeployService after = restarted(900);

        after.agentStatus(KEY, build.getId(), "HEALTH", "health: ok", "");
        after.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");

        Build saved = store.find(build.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(Build.Status.SUCCEEDED);
        assertThat(saved.getUrl()).isEqualTo("https://blog.example");
    }

    @Test
    void 재시작한_뒤에도_다른_에이전트의_상태는_받지_않는다() {
        Build build = service.start(KEY, request("blog-1b62c0"));
        AgentDeployService after = restarted(900);

        after.agentStatus("ffffffffffff", build.getId(), "SUCCEEDED", "done", "https://evil.example");

        assertThat(store.find(build.getId()).orElseThrow().getStatus()).isNotEqualTo(Build.Status.SUCCEEDED);
    }

    @Test
    void 재시작하면_진행_중인_빌드를_다시_등록해서_응답이_없으면_제한_시간_뒤에_닫는다() {
        Build build = service.start(KEY, request("blog-1b62c0"));
        AgentDeployService after = restarted(0);

        after.expire();
        assertThat(store.find(build.getId()).orElseThrow().getStatus()).isNotEqualTo(Build.Status.FAILED);

        after.resumeInFlight();
        after.expire();
        assertThat(store.find(build.getId()).orElseThrow().getStatus()).isEqualTo(Build.Status.FAILED);
    }

    @Test
    void 끝난_빌드는_다시_등록하지_않는다() {
        Build build = service.start(KEY, request("blog-1b62c0"));
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");
        AgentDeployService after = restarted(0);

        after.resumeInFlight();
        after.agentStatus(KEY, build.getId(), "FAILED", "late", "");
        after.expire();

        assertThat(store.find(build.getId()).orElseThrow().getStatus()).isEqualTo(Build.Status.SUCCEEDED);
    }

    @Test
    void 버스팅_설정을_그_앱의_에이전트에_보낸다() throws Exception {
        Build build = service.start(KEY, request("blog-1b62c0"));
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");
        when(hub.supports(KEY, "burst")).thenReturn(true);
        when(hub.burst(KEY, "blog-1b62c0")).thenReturn(new AgentHub.Burst(true, true, null, Map.of(), ""));

        assertThat(service.burst("blog-1b62c0", true, 30)).isPresent();

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(hub, org.mockito.Mockito.atLeastOnce()).send(eq(KEY), sent.capture());
        JsonNode message = new ObjectMapper().readTree(sent.getValue());
        assertThat(message.path("type").asText()).isEqualTo("burst");
        assertThat(message.path("enabled").asBoolean()).isTrue();
        assertThat(message.path("cloudPercent").asInt()).isEqualTo(30);
        assertThat(service.burst("other-app", true, 0)).isEmpty();
    }

    @Test
    void 버스팅을_모르는_에이전트에는_보내지_않는다() {
        Build build = service.start(KEY, request("blog-1b62c0"));
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");

        assertThatThrownBy(() -> service.burst("blog-1b62c0", true, 30)).hasMessageContaining("최신 이미지");
        assertThatThrownBy(() -> service.burst("blog-1b62c0", true, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 에이전트가_요청한_대기_배포가_최신이어도_그_에이전트의_앱이다() {
        Build build = service.start(KEY, request("blog-1b62c0"));
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");
        Build standby = new Build("s1", request("blog-1b62c0"));
        standby.log(AgentBurst.STANDBY_MARK + KEY);
        standby.status(Build.Status.SUCCEEDED, "done");
        store.save(standby);

        assertThat(service.ownedBy(KEY, "blog-1b62c0")).isTrue();
        assertThat(service.latestApp(KEY)).contains("blog-1b62c0");
        assertThat(service.latestApp("ffffffffffff")).isEmpty();
        assertThat(service.ownedBy("ffffffffffff", "blog-1b62c0")).isFalse();
    }

    @Test
    void 버스팅_상태에_에이전트가_기다리는_클라우드_빌드_진행을_붙인다() throws Exception {
        Build build = service.start(KEY, request("blog-1b62c0"));
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");
        Build standby = new Build("s1", request("blog-1b62c0"));
        standby.status(Build.Status.BUILDING, "build: kaniko job build-s1");
        store.save(standby);
        JsonNode state = new ObjectMapper().readTree("{\"app\":\"blog-1b62c0\",\"homeBuild\":\"s1\",\"standbyBuild\":\"\"}");
        when(hub.burst(KEY, "blog-1b62c0")).thenReturn(new AgentHub.Burst(true, true, state, Map.of(), "blog-1b62c0"));

        AgentHub.Burst burst = service.burstState("blog-1b62c0").orElseThrow();

        assertThat(burst.builds()).containsOnlyKeys("homeBuild");
        assertThat(burst.builds().get("homeBuild").status()).isEqualTo("BUILDING");
        assertThat(burst.builds().get("homeBuild").line()).contains("kaniko");
    }

    @Test
    void 진행_중인_빌드를_취소하면_에이전트에_cancel을_보내고_CANCELLED로_닫는다() throws Exception {
        when(hub.supports(KEY, "cancel")).thenReturn(true);
        Build build = service.start(KEY, request("blog-1b62c0"));
        service.agentStatus(KEY, build.getId(), "BUILDING", "build: docker build", "");

        Build cancelled = service.cancel(build.getId());

        assertThat(cancelled.getStatus()).isEqualTo(Build.Status.CANCELLED);
        assertThat(cancelled.getLogs()).contains("cancel: 에이전트에 보냈다", "cancelled: 사용자가 취소했다");
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(hub, org.mockito.Mockito.times(2)).send(eq(KEY), sent.capture());
        JsonNode message = new ObjectMapper().readTree(sent.getValue());
        assertThat(message.path("type").asText()).isEqualTo("cancel");
        assertThat(message.path("id").asText()).isEqualTo(build.getId());
    }

    @Test
    void 취소한_뒤에_에이전트가_보낸_단계와_결과는_받지_않고_CANCELLED_그대로() {
        when(hub.supports(KEY, "cancel")).thenReturn(true);
        Build build = service.start(KEY, request("blog-1b62c0"));
        service.cancel(build.getId());

        service.agentStatus(KEY, build.getId(), "HEALTH", "health: ok", "");
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");
        restarted(0).agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");

        Build saved = store.find(build.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(Build.Status.CANCELLED);
        assertThat(saved.getUrl()).isNull();
    }

    @Test
    void 에이전트가_끊겨_있으면_cancel을_보내지_않고_기록만_CANCELLED로_닫는다() {
        Build build = service.start(KEY, request("blog-1b62c0"));
        when(hub.connected(KEY)).thenReturn(false);

        Build cancelled = service.cancel(build.getId());

        assertThat(cancelled.getStatus()).isEqualTo(Build.Status.CANCELLED);
        assertThat(cancelled.getLogs()).contains("cancel: 에이전트가 연결돼 있지 않아 기록만 닫는다");
        verify(hub, org.mockito.Mockito.times(1)).send(eq(KEY), anyString());
    }

    @Test
    void 잡을_보내기_전에_취소되면_에이전트에_잡을_보내지_않는다() {
        when(github.resolveCommit(any())).thenAnswer(call -> {
            service.cancel(store.findAll().get(0).getId());
            return COMMIT;
        });

        Build build = service.start(KEY, request("blog-1b62c0"));

        assertThat(store.find(build.getId()).orElseThrow().getStatus()).isEqualTo(Build.Status.CANCELLED);
        verify(hub, never()).send(eq(KEY), org.mockito.ArgumentMatchers.contains("\"type\":\"job\""));
    }

    @Test
    void 끝난_온프레미스_빌드를_취소하면_IllegalStateException이고_상태는_그대로() {
        Build build = service.start(KEY, request("blog-1b62c0"));
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");

        assertThatThrownBy(() -> service.cancel(build.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SUCCEEDED");
        assertThat(store.find(build.getId()).orElseThrow().getStatus()).isEqualTo(Build.Status.SUCCEEDED);
    }

    @Test
    void 거점_전환_취소를_그_앱의_에이전트에_보낸다() throws Exception {
        Build build = service.start(KEY, request("blog-1b62c0"));
        service.agentStatus(KEY, build.getId(), "SUCCEEDED", "done", "https://blog.example");
        when(hub.burst(KEY, "blog-1b62c0")).thenReturn(new AgentHub.Burst(true, true, null, Map.of(), ""));

        assertThatThrownBy(() -> service.cancelHome("blog-1b62c0")).hasMessageContaining("최신 이미지");

        when(hub.supports(KEY, "home-cancel")).thenReturn(true);
        assertThat(service.cancelHome("blog-1b62c0")).isPresent();
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(hub, org.mockito.Mockito.atLeastOnce()).send(eq(KEY), sent.capture());
        JsonNode message = new ObjectMapper().readTree(sent.getValue());
        assertThat(message.path("type").asText()).isEqualTo("home-cancel");
        assertThat(message.path("app").asText()).isEqualTo("blog-1b62c0");
    }
}
