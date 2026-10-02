package com.lily.builder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 빌드 → lily-cicd 호출 → 상태 기록 순서 */
class BuildServiceTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    private final KanikoBuilder kaniko = mock(KanikoBuilder.class);
    private final GitHubSource github = mock(GitHubSource.class);
    private final RestClient.Builder http = RestClient.builder().baseUrl("http://cicd");
    // 배포 중에는 진행 단계 조회(GET .../progress)가 다른 스레드에서 1초마다 온다. 배포 요청과 순서가 정해지지 않는다
    private final MockRestServiceServer cicd = MockRestServiceServer.bindTo(http).ignoreExpectOrder(true).build();
    private final InMemoryBuildStore store = new InMemoryBuildStore();
    /** null 이면 진행 조회는 404. 응답이 끊긴 배포를 따라갈 때만 본문을 넣는다 */
    private final AtomicReference<String> progressBody = new AtomicReference<>();
    private final BuildService service = new BuildService(store, kaniko, new CicdClient(http.build()), new SyncRunner(),
            // ECR 이 아닌 레지스트리라 저장소 생성은 건너뛴다
            new EcrRepositories(new BuilderProperties("ns", "localhost:5000", true, "http://cicd", "kaniko", 10,
                    new BuilderProperties.Dynamodb("t", null, "ap-northeast-2", false), "")), github);

    @BeforeEach
    void progress() {
        // 진행 단계는 로그 보조용이라 없어도 배포는 계속된다. 몇 번 오든(0번 포함) 404 로 답한다
        cicd.expect(ExpectedCount.between(0, 1000), requestTo(matchesPattern(".*/api/deployments/[^/]+/progress")))
                .andRespond(request -> {
                    String body = progressBody.get();
                    if (body == null) {
                        return withStatus(HttpStatus.NOT_FOUND).createResponse(request);
                    }
                    return withSuccess(body, MediaType.APPLICATION_JSON).createResponse(request);
                });
    }

    @BeforeEach
    void source() {
        when(github.resolveCommit(any())).thenReturn(COMMIT);
        when(github.migrations(any(), eq(COMMIT))).thenReturn(Map.of());
        // 레포에 Dockerfile 이 있는 경우가 기본. 없는 경우는 따로 시험한다
        when(github.file(any(), eq(COMMIT), eq("Dockerfile"))).thenReturn("FROM scratch");
    }

    private static BuildRequest request(String database) {
        return new BuildRequest("https://github.com/org/repo", null, null, null, "blog", 8080,
                database, null, null, Map.of("SPRING_PROFILES_ACTIVE", "local"));
    }

    @Test
    void 빌드_후_cicd_에_배포를_요청한다() {
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/blog:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"appName":"blog","imageUrl":"reg/blog:t","targetPort":8080,"database":"postgres",
                         "extraEnv":{"SPRING_PROFILES_ACTIVE":"local"}}"""))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","activeColor":"blue","targetHostUrl":"http://blog.domain.com","logs":["ok"]}""",
                        MediaType.APPLICATION_JSON));

        Build build = service.start(request("postgres"));

        assertThat(build.getStatus()).isEqualTo(Build.Status.SUCCEEDED);
        assertThat(build.getImage()).isEqualTo("reg/blog:t");
        assertThat(build.getUrl()).isEqualTo("http://blog.domain.com");
        assertThat(build.getLogs()).contains("cicd: ok");
        cicd.verify();
    }

    /** 공개 주소 CNAME 내용물이 content 인 존. 대기 배포가 끝날 때 거점을 본다 */
    private BuildService withAddress(String content) {
        String records = "[{\"id\":\"r1\",\"type\":\"CNAME\",\"name\":\"blog.lilycloud.kr\",\"content\":\"" + content + "\"}]";
        AppAddress addresses = new AppAddress(new PlatformProperties.Cloudflare("t", "a", "z", "lilycloud.kr"),
                "alb.example.net", (method, path, body) -> {
                    try {
                        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(records);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
        return new BuildService(store, kaniko, new CicdClient(http.build()), new SyncRunner(),
                new EcrRepositories(new BuilderProperties("ns", "localhost:5000", true, "http://cicd", "kaniko", 10,
                        new BuilderProperties.Dynamodb("t", null, "ap-northeast-2", false), "")), github,
                new DeployFollow(new CicdClient(http.build())), BuildService.offlineConfig(), BuildService.offlineDiagnoser(),
                addresses);
    }

    private static BuildRequest standby() {
        return new BuildRequest("https://github.com/org/repo", null, null, null, "blog", 8080,
                "", null, null, Map.of(), "blog.lilycloud.kr", true, null, null, null);
    }

    @Test
    void 대기_배포가_끝나면_레플리카를_0_으로_내린다() {
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/blog:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andRespond(withSuccess("{\"status\":\"SUCCESS\"}", MediaType.APPLICATION_JSON));
        cicd.expect(requestTo("http://cicd/api/apps/blog/replicas"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        Build build = withAddress("11111111-2222-3333-4444-555555555555.cfargotunnel.com").start(standby());

        assertThat(build.getLogs()).contains("standby: scaled to 0");
        cicd.verify();
    }

    @Test
    void 공개_주소가_클라우드면_대기_배포가_끝나도_내리지_않는다() {
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/blog:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andRespond(withSuccess("{\"status\":\"SUCCESS\"}", MediaType.APPLICATION_JSON));

        Build build = withAddress("alb.example.net").start(standby());

        assertThat(build.getStatus()).isEqualTo(Build.Status.SUCCEEDED);
        assertThat(build.getLogs()).contains("standby: public address points to the cloud, replicas kept");
        cicd.verify();
    }

    @Test
    void 커밋을_고정하고_마이그레이션을_함께_보낸다() {
        when(github.migrations(any(), eq(COMMIT))).thenReturn(Map.of("V1__init.sql", "create table a(id int);"));
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/blog:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andExpect(content().json("""
                        {"appName":"blog","migrations":{"V1__init.sql":"create table a(id int);"}}"""))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","activeColor":"blue","schemaVersion":"1","logs":["schema: migrated none -> 1"]}""",
                        MediaType.APPLICATION_JSON));

        Build build = service.start(request("postgres"));

        assertThat(build.getStatus()).isEqualTo(Build.Status.SUCCEEDED);
        assertThat(build.getLogs()).contains("source: commit " + COMMIT, "source: migrations [V1__init.sql]",
                "cicd: schema: migrated none -> 1");
        cicd.verify();
    }

    @Test
    void 마이그레이션이_없으면_migrations_를_보내지_않는다() {
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/blog:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andExpect(request -> assertThat(((MockClientHttpRequest) request).getBodyAsString()).doesNotContain("migrations"))
                .andRespond(withSuccess("{\"status\":\"SUCCESS\"}", MediaType.APPLICATION_JSON));

        Build build = service.start(request(""));

        assertThat(build.getLogs()).contains("source: migrations none (src/main/resources/db/migration)");
        cicd.verify();
    }

    @Test
    void 브랜치를_찾지_못하면_빌드하지_않는다() {
        when(github.resolveCommit(any())).thenThrow(new IllegalStateException("브랜치 main 를 찾지 못했다"));

        Build build = service.start(request(""));

        assertThat(build.getStatus()).isEqualTo(Build.Status.FAILED);
        assertThat(build.getLogs()).anyMatch(l -> l.contains("브랜치 main 를 찾지 못했다"));
        verifyNoInteractions(kaniko);
    }

    @Test
    void DB_를_고르지_않으면_database_를_보내지_않는다() {
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/blog:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andExpect(content().json("{\"database\":null}"))
                .andRespond(withSuccess("{\"status\":\"SUCCESS\"}", MediaType.APPLICATION_JSON));

        assertThat(service.start(request("")).getStatus()).isEqualTo(Build.Status.SUCCEEDED);
        cicd.verify();
    }

    @Test
    void 빌드가_실패하면_배포하지_않는다() {
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenThrow(new IllegalStateException("kaniko build failed"));

        Build build = service.start(request(""));

        assertThat(build.getStatus()).isEqualTo(Build.Status.FAILED);
        assertThat(build.getLogs()).anyMatch(l -> l.contains("kaniko build failed"));
        cicd.verify();
    }

    @Test
    void 배포가_실패하면_cicd_응답을_남긴다() {
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/blog:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("{\"status\":\"FAILED\",\"message\":\"ready timeout\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        Build build = service.start(request(""));

        assertThat(build.getStatus()).isEqualTo(Build.Status.FAILED);
        assertThat(build.getLogs()).anyMatch(l -> l.contains("ready timeout"));
        assertThat(service.get(build.getId())).containsSame(build);
    }

    @Test
    void Ready_실패면_cicd_로그를_줄로_남기고_빠진_설정을_고칠_방법으로_돌려준다() {
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/blog:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("""
                                {"status":"FAILED","message":"target deployment 가 120초 안에 Ready 가 되지 않음: blog-blue",
                                 "logs":["step2: applied deployment blog-blue",
                                         "diagnosis: container blog restarts=3 waiting=CrashLoopBackOff lastExit=1 Error",
                                         "diagnosis: log Caused by: PlaceholderResolutionException: Could not resolve placeholder 'jwt.secret' in value"]}""")
                        .contentType(MediaType.APPLICATION_JSON));

        Build build = service.start(request(""));

        assertThat(build.getStatus()).isEqualTo(Build.Status.FAILED);
        assertThat(build.getLogs()).contains("cicd: step2: applied deployment blog-blue")
                .noneMatch(l -> l.contains("{\"status\""));
        assertThat(build.getDiagnosis().cause()).contains("JWT_SECRET");
        assertThat(build.getDiagnosis().fixes()).singleElement().satisfies(fix -> {
            assertThat(fix.env()).isEqualTo("JWT_SECRET");
            assertThat(fix.kind()).isEqualTo(ConfigAdvisor.Kind.GENERATE);
        });
        assertThat(build.getDiagnosis().isAutoFixable()).isTrue();
    }

    @Test
    void 배포_응답이_끊기면_POST를_다시_보내지_않고_진행_상태를_따른다() {
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/blog:t");
        progressBody.set("""
                {"stage":"succeeded","detail":"cutover complete. url=https://blog.example","updatedAt":"2026-10-01T00:00:00Z","image":"reg/blog:t"}""");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    throw new java.net.SocketTimeoutException("reset");
                });

        Build build = service.start(request(""));

        assertThat(build.getStatus()).isEqualTo(Build.Status.SUCCEEDED);
        assertThat(build.getUrl()).isEqualTo("https://blog.example");
        assertThat(build.getLogs()).anyMatch(line -> line.contains("response lost"));
        cicd.verify();
    }

    @Test
    void 재시작_뒤_남은_클라우드_배포만_진행_상태로_닫는다() {
        progressBody.set("""
                {"stage":"succeeded","detail":"cutover complete. url=https://blog.example","updatedAt":"2026-10-01T00:00:00Z","image":"reg/blog:t"}""");
        Build cloud = new Build("cloud123", request(""));
        cloud.image("reg/blog:t");
        cloud.status(Build.Status.DEPLOYING, "deploy: lily-cicd");
        store.save(cloud);
        Build agent = new Build("agent123", request(""));
        agent.status(Build.Status.DEPLOYING, "agent: STARTING");
        store.save(agent);

        service.resumeInterrupted();

        assertThat(cloud.getStatus()).isEqualTo(Build.Status.SUCCEEDED);
        assertThat(cloud.getUrl()).isEqualTo("https://blog.example");
        assertThat(cloud.getLogs()).anyMatch(line -> line.contains("builder restarted"));
        assertThat(agent.getStatus()).isEqualTo(Build.Status.DEPLOYING);
    }

    @Test
    void 이력은_저장소에_남고_최신순이다() throws Exception {
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenThrow(new IllegalStateException("x"));

        Build first = service.start(request(""));
        Thread.sleep(5);
        Build second = service.start(request(""));

        assertThat(service.history()).extracting(Build::getId).containsExactly(second.getId(), first.getId());
        // 접수, 빌드 시작, 실패 때마다 저장한다
        assertThat(store.saves).isGreaterThanOrEqualTo(6);
    }

    static class SyncRunner extends BuildService.BuildRunner {
        @Override
        public void run(Runnable task) {
            task.run();
        }
    }

    private static BuildRequest auto(Integer port) {
        return new BuildRequest("https://github.com/org/repo", null, null, null, "web", port,
                "auto", null, null, Map.of());
    }

    @Test
    void 포트와_DB_가_비거나_auto_면_레포를_보고_정한다() {
        when(github.file(any(), eq(COMMIT), eq("Dockerfile"))).thenReturn("FROM node:24\nEXPOSE 3000");
        when(github.file(any(), eq(COMMIT), eq("package.json"))).thenReturn("{\"dependencies\":{\"pg\":\"^8\"}}");
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/web:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andExpect(content().json("""
                        {"appName":"web","targetPort":3000,"database":"postgres",
                         "readinessPath":"tcp","livenessPath":"tcp"}"""))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","activeColor":"blue","logs":[]}""", MediaType.APPLICATION_JSON));

        Build build = service.start(auto(null));

        assertThat(build.getStatus()).isEqualTo(Build.Status.SUCCEEDED);
        assertThat(build.getDatabase()).isEqualTo("postgres");
        assertThat(build.getLogs()).contains(
                "detect: port 3000 (Dockerfile EXPOSE)",
                "detect: database postgres (package.json: \"pg\")",
                "detect: health tcp port 3000 (no spring actuator)");
        cicd.verify();
    }

    @Test
    void auto_인데_드라이버가_없으면_DB_없이_EXPOSE_가_없으면_8080() {
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/web:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andExpect(content().json("""
                        {"targetPort":8080,"database":null}"""))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","activeColor":"blue","logs":[]}""", MediaType.APPLICATION_JSON));

        Build build = service.start(auto(null));

        assertThat(build.getDatabase()).isNull();
        assertThat(build.getLogs()).contains("detect: port 8080 (default, no EXPOSE)",
                "detect: database none (no driver found)");
        cicd.verify();
    }

    @Test
    void Spring_actuator_가_있으면_헬스_경로는_lily_cicd_기본값() {
        when(github.file(any(), eq(COMMIT), eq("build.gradle")))
                .thenReturn("implementation 'org.springframework.boot:spring-boot-starter-actuator'");
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/web:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andExpect(content().json("""
                        {"targetPort":8080,"readinessPath":null,"livenessPath":null}"""))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","activeColor":"blue","logs":[]}""", MediaType.APPLICATION_JSON));

        Build build = service.start(auto(8080));

        assertThat(build.getLogs()).noneMatch(line -> line.startsWith("detect: health"));
        cicd.verify();
    }

    @Test
    void Dockerfile_이_없으면_빌드_파일로_만들어_빌드하고_포트도_그걸로_정한다() {
        when(github.file(any(), eq(COMMIT), eq("Dockerfile"))).thenReturn(null);
        when(github.file(any(), eq(COMMIT), eq("package.json")))
                .thenReturn("{\"scripts\":{\"build\":\"vite build\"},\"devDependencies\":{\"vite\":\"^8\"}}");
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/web:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andExpect(content().json("""
                        {"targetPort":8080}"""))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","activeColor":"blue","logs":[]}""", MediaType.APPLICATION_JSON));

        Build build = service.start(auto(null));

        assertThat(build.getStatus()).isEqualTo(Build.Status.SUCCEEDED);
        assertThat(build.getLogs()).contains("source: no Dockerfile, generated for node static (dist)",
                "detect: port 8080 (Dockerfile EXPOSE)");
        verify(kaniko).build(anyString(), any(), anyString(), eq(COMMIT),
                argThat((String dockerfile) -> dockerfile.contains("COPY --from=build /src/dist")));
        cicd.verify();
    }

    @Test
    void Dockerfile_도_빌드_파일도_없으면_빌드하지_않고_실패한다() {
        when(github.file(any(), eq(COMMIT), eq("Dockerfile"))).thenReturn(null);

        Build build = service.start(auto(null));

        assertThat(build.getStatus()).isEqualTo(Build.Status.FAILED);
        assertThat(build.getLogs()).anyMatch(line -> line.contains("빌드 방법도 찾지 못했다"));
        verifyNoInteractions(kaniko);
    }

    @Test
    void 배포_전_감지는_빌드하지_않고_DB_와_근거만_돌려준다() {
        repo(Map.of("backend/build.gradle", "implementation 'org.postgresql:postgresql'"));
        when(github.folders(any(), eq(COMMIT))).thenReturn(java.util.List.of("backend"));

        BuildService.Detection found = service.inspect(
                new DetectRequest("https://github.com/org/repo", null, null, null));

        assertThat(found.database()).isEqualTo("postgres");
        assertThat(found.databaseSource()).isEqualTo("build.gradle: org.postgresql");
        assertThat(found.dir()).isEqualTo("backend");
        verifyNoInteractions(kaniko);
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void 배포_전_감지에서_드라이버가_없으면_DB_는_null() {
        BuildService.Detection found = service.inspect(
                new DetectRequest("https://github.com/org/repo", null, null, null));

        assertThat(found.database()).isNull();
        assertThat(found.dir()).isNull();
    }

    /** 레포 파일 (레포 루트 기준 경로). 요청의 rootDir 를 붙여 찾는다 */
    private void repo(Map<String, String> files) {
        when(github.file(any(), eq(COMMIT), anyString())).thenAnswer(call -> {
            BuildRequest r = call.getArgument(0);
            String root = r.rootDir() == null || r.rootDir().isBlank() ? "" : r.rootDir() + "/";
            return files.get(root + call.getArgument(2));
        });
    }

    @Test
    void 루트에_빌드_파일이_없고_백엔드와_프론트_폴더가_있으면_한_이미지로_묶어_주소_하나로_띄운다() {
        repo(Map.of(
                "backend/pom.xml", "<project><dependency>org.postgresql</dependency><java.version>17</java.version></project>",
                "frontend/package.json", "{\"scripts\":{\"build\":\"vite build\"},\"devDependencies\":{\"vite\":\"^7\"}}",
                "frontend/package-lock.json", "{}"));
        when(github.folders(any(), eq(COMMIT))).thenReturn(java.util.List.of("backend", "docs", "frontend"));
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/web:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andExpect(content().json("""
                        {"targetPort":3000}"""))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","activeColor":"blue","logs":[]}""", MediaType.APPLICATION_JSON));

        Build build = service.start(auto(null));

        assertThat(build.getStatus()).isEqualTo(Build.Status.SUCCEEDED);
        assertThat(build.getLogs()).anyMatch(line -> line.startsWith("source: backend backend + frontend frontend in one image"))
                .contains("detect: port 3000 (Dockerfile EXPOSE)",
                        "detect: database postgres (pom.xml: org.postgresql)");
        // 레포 루트를 컨텍스트로 빌드하고, 마이그레이션은 백엔드 폴더에서 찾는다
        verify(kaniko).build(anyString(), argThat((BuildRequest r) -> r.rootDir() == null), anyString(), eq(COMMIT),
                argThat((String dockerfile) -> dockerfile.contains("COPY backend/ .") && dockerfile.contains("COPY frontend/ .")));
        verify(github).migrations(argThat(r -> "backend/src/main/resources/db/migration".equals(GitHubSource.folder(r))), eq(COMMIT));
        cicd.verify();
    }

    @Test
    void 루트에_빌드_파일이_없고_앱_폴더가_하나면_그_폴더를_빌드한다() {
        repo(Map.of("web/package.json", "{\"scripts\":{\"build\":\"vite build\"},\"devDependencies\":{\"vite\":\"^7\"}}"));
        when(github.folders(any(), eq(COMMIT))).thenReturn(java.util.List.of("docs", "web"));
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/web:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","activeColor":"blue","logs":[]}""", MediaType.APPLICATION_JSON));

        Build build = service.start(auto(null));

        assertThat(build.getStatus()).isEqualTo(Build.Status.SUCCEEDED);
        assertThat(build.getLogs()).contains("source: app in folder web, no Dockerfile, generated for node static (dist)");
        verify(kaniko).build(anyString(), argThat((BuildRequest r) -> "web".equals(r.rootDir())), anyString(), eq(COMMIT), any());
    }

    @Test
    void 루트가_모바일_앱이면_하위_서버_폴더를_빌드한다() {
        repo(Map.of(
                "package.json", "{\"main\":\"expo-router/entry\",\"scripts\":{\"start\":\"expo start\"},\"dependencies\":{\"expo\":\"~57\",\"react-native\":\"0.86\"}}",
                "server/requirements.txt", "fastapi==0.116.1\nuvicorn[standard]==0.35.0\n",
                "server/app/main.py", "from fastapi import FastAPI\napp = FastAPI()\n"));
        when(github.folders(any(), eq(COMMIT))).thenReturn(java.util.List.of("app", "server"));
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/mole:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","activeColor":"blue","logs":[]}""", MediaType.APPLICATION_JSON));

        Build build = service.start(auto(null));

        assertThat(build.getStatus()).isEqualTo(Build.Status.SUCCEEDED);
        assertThat(build.getLogs()).contains("source: root is expo app, looking at sub folders")
                .anyMatch(line -> line.startsWith("source: app in folder server") && line.contains("fastapi"));
        verify(kaniko).build(anyString(), argThat((BuildRequest r) -> "server".equals(r.rootDir())), anyString(), eq(COMMIT), any());
    }

    @Test
    void 데스크톱_앱_폴더는_빼고_서버_앱_하나를_빌드한다() {
        repo(Map.of(
                "desktop/package.json", "{\"main\":\"out/main.js\",\"scripts\":{\"start\":\"electron .\"},\"devDependencies\":{\"electron\":\"^44\"}}",
                "web/package.json", "{\"scripts\":{\"build\":\"next build\",\"start\":\"next start\"},\"dependencies\":{\"next\":\"16\"}}"));
        when(github.folders(any(), eq(COMMIT))).thenReturn(java.util.List.of("desktop", "web"));
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/web:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","activeColor":"blue","logs":[]}""", MediaType.APPLICATION_JSON));

        Build build = service.start(auto(null));

        assertThat(build.getStatus()).isEqualTo(Build.Status.SUCCEEDED);
        assertThat(build.getLogs()).contains("source: skipped desktop (electron app)")
                .anyMatch(line -> line.startsWith("source: app in folder web"));
    }

    @Test
    void 앱_폴더가_여러_개라_하나로_정할_수_없으면_폴더를_알려주고_실패한다() {
        repo(Map.of("api/go.mod", "module a\n\ngo 1.23\n", "worker/go.mod", "module b\n\ngo 1.23\n"));
        when(github.folders(any(), eq(COMMIT))).thenReturn(java.util.List.of("api", "worker"));

        Build build = service.start(auto(null));

        assertThat(build.getStatus()).isEqualTo(Build.Status.FAILED);
        assertThat(build.getLogs()).anyMatch(line -> line.contains("앱 폴더가 여러 개") && line.contains("api go") && line.contains("worker go"));
        verifyNoInteractions(kaniko);
        assertThat(build.getDiagnosis().fixes()).singleElement().satisfies(fix -> {
            assertThat(fix.type()).isEqualTo("rootDir");
            assertThat(fix.options()).containsExactly("api", "worker");
        });
    }

    @Test
    void 레포에_Dockerfile_이_있으면_그대로_쓴다() {
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/blog:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","activeColor":"blue","logs":[]}""", MediaType.APPLICATION_JSON));

        service.start(request("postgres"));

        verify(kaniko).build(anyString(), any(), anyString(), eq(COMMIT), isNull());
    }

    @Test
    void 값을_모두_주면_Dockerfile_말고는_레포를_읽지_않는다() {
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any())).thenReturn("reg/blog:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andExpect(content().json("""
                        {"targetPort":9000,"database":"mysql","readinessPath":"/ready","livenessPath":"/live"}"""))
                .andRespond(withSuccess("""
                        {"status":"SUCCESS","activeColor":"blue","logs":[]}""", MediaType.APPLICATION_JSON));

        service.start(new BuildRequest("https://github.com/org/repo", null, null, null, "blog", 9000,
                "mysql", "/ready", "/live", Map.of()));

        verify(github, never()).file(any(), anyString(), not(eq("Dockerfile")));
        cicd.verify();
    }
}
