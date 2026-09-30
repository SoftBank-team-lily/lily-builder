package com.lily.builder;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 빌드 → lily-cicd 호출 → 상태 기록 순서 */
class BuildServiceTest {

    private final KanikoBuilder kaniko = mock(KanikoBuilder.class);
    private final RestClient.Builder http = RestClient.builder().baseUrl("http://cicd");
    private final MockRestServiceServer cicd = MockRestServiceServer.bindTo(http).build();
    private final InMemoryBuildStore store = new InMemoryBuildStore();
    private final BuildService service = new BuildService(store, kaniko, new CicdClient(http.build()), new SyncRunner(),
            // ECR 이 아닌 레지스트리라 저장소 생성은 건너뛴다
            new EcrRepositories(new BuilderProperties("ns", "localhost:5000", true, "http://cicd", "kaniko", 10,
                    new BuilderProperties.Dynamodb("t", null, "ap-northeast-2", false), "")));

    private static BuildRequest request(String database) {
        return new BuildRequest("https://github.com/org/repo", null, null, null, "blog", 8080,
                database, null, null, Map.of("SPRING_PROFILES_ACTIVE", "local"));
    }

    @Test
    void 빌드_후_cicd_에_배포를_요청한다() {
        when(kaniko.build(anyString(), any(), anyString())).thenReturn("reg/blog:t");
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

    @Test
    void DB_를_고르지_않으면_database_를_보내지_않는다() {
        when(kaniko.build(anyString(), any(), anyString())).thenReturn("reg/blog:t");
        cicd.expect(requestTo("http://cicd/api/deployments"))
                .andExpect(content().json("{\"database\":null}"))
                .andRespond(withSuccess("{\"status\":\"SUCCESS\"}", MediaType.APPLICATION_JSON));

        assertThat(service.start(request("")).getStatus()).isEqualTo(Build.Status.SUCCEEDED);
        cicd.verify();
    }

    @Test
    void 빌드가_실패하면_배포하지_않는다() {
        when(kaniko.build(anyString(), any(), anyString())).thenThrow(new IllegalStateException("kaniko build failed"));

        Build build = service.start(request(""));

        assertThat(build.getStatus()).isEqualTo(Build.Status.FAILED);
        assertThat(build.getLogs()).anyMatch(l -> l.contains("kaniko build failed"));
        cicd.verify();
    }

    @Test
    void 배포가_실패하면_cicd_응답을_남긴다() {
        when(kaniko.build(anyString(), any(), anyString())).thenReturn("reg/blog:t");
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
    void 이력은_저장소에_남고_최신순이다() throws Exception {
        when(kaniko.build(anyString(), any(), anyString())).thenThrow(new IllegalStateException("x"));

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
}
