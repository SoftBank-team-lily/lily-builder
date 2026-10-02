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

    private static BuildRequest importing(String appName, String database) {
        return new BuildRequest("https://github.com/org/blog", null, null, null, appName, 8080, database, "/", "/",
                Map.of(), null, null, null, null, null, "local", null, null, true);
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
        when(hub.burst(KEY, "blog-1b62c0")).thenReturn(new AgentHub.Burst(true, true, null));

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
}
