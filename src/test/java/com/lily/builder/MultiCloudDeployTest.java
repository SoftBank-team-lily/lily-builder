package com.lily.builder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 멀티클라우드 배포: 한 번 빌드 → GCP(DB·마이그레이션) → AWS(릴레이로 GCP DB) → 별칭과 엣지 비율 */
class MultiCloudDeployTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String RELAY = "db-relay-gcp.lily-builds.svc.cluster.local";

    private final KanikoBuilder kaniko = mock(KanikoBuilder.class);
    private final GitHubSource github = mock(GitHubSource.class);
    private final EdgeWorker edge = mock(EdgeWorker.class);
    private final CloudClients clouds = mock(CloudClients.class);
    private final ProvisionerClient gcpProvisioner = mock(ProvisionerClient.class);
    private final RestClient.Builder awsHttp = RestClient.builder().baseUrl("http://aws-cicd");
    private final MockRestServiceServer aws = MockRestServiceServer.bindTo(awsHttp).ignoreExpectOrder(true).build();
    private final RestClient.Builder gcpHttp = RestClient.builder().baseUrl("http://gcp-cicd");
    private final MockRestServiceServer gcp = MockRestServiceServer.bindTo(gcpHttp).ignoreExpectOrder(true).build();
    private final InMemoryBuildStore store = new InMemoryBuildStore();
    private BuildService service;

    @BeforeEach
    void setUp() {
        when(github.resolveCommit(any())).thenReturn(COMMIT);
        when(github.migrations(any(), eq(COMMIT))).thenReturn(Map.of("01_create_posts.yaml", "operations: []"));
        when(github.file(any(), eq(COMMIT), eq("Dockerfile"))).thenReturn("FROM scratch");
        when(kaniko.defaultDestination()).thenReturn(new KanikoBuilder.Destination("ecr", "ecr-pull"));
        when(kaniko.build(anyString(), any(), anyString(), eq(COMMIT), any(), anyList()))
                .thenReturn(List.of("ar/blog:t", "ecr/blog:t"));
        when(clouds.deployConfigured()).thenReturn(true);
        when(clouds.cicd()).thenReturn(new CicdClient(gcpHttp.build()));
        when(clouds.provisioner()).thenReturn(gcpProvisioner);
        when(clouds.registry()).thenReturn("ar");
        when(clouds.registrySecret()).thenReturn("gcp-pull");
        when(clouds.relayHost()).thenReturn(RELAY);
        when(clouds.relayPort()).thenReturn(5432);
        when(clouds.origin()).thenReturn("gcp.lilycloud.kr");
        when(gcpProvisioner.ensure("blog", "postgres", RELAY, 5432)).thenReturn(new ProvisionerClient.Connection("db1",
                Map.of("DATABASE_URL", "postgresql://u:p@" + RELAY + ":5432/blog")));
        when(edge.multiReady()).thenReturn(true);
        when(edge.multiHost("blog", "gcp")).thenReturn("blog-gcp.lilycloud.kr");
        when(edge.multiHost("blog", "aws")).thenReturn("blog-aws.lilycloud.kr");
        when(edge.attachMulti("blog", BuildService.INITIAL_GCP_PERCENT)).thenReturn("edge: multi");
        for (MockRestServiceServer server : List.of(aws, gcp)) {
            server.expect(ExpectedCount.between(0, 1000), requestTo(matchesPattern(".*/api/deployments/[^/]+/progress")))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND));
        }
        CicdClient awsCicd = new CicdClient(awsHttp.build());
        service = new BuildService(store, kaniko, awsCicd, new BuildServiceTest.SyncRunner(),
                new EcrRepositories(new BuilderProperties("ns", "localhost:5000", true, "http://cicd", "kaniko", 10,
                        new BuilderProperties.Dynamodb("t", null, "ap-northeast-2", false), "")), github,
                new DeployFollow(awsCicd), BuildService.offlineConfig(), BuildService.offlineDiagnoser(),
                AppAddress.disabled(), edge, clouds);
    }

    private static BuildRequest multi(String database) {
        return new BuildRequest("https://github.com/org/repo", null, null, null, "blog", 8000, database, null, null,
                Map.of(), null, null, null, null, null, null, null, null, null, null, "MULTI");
    }

    private static String ok(String url) {
        return "{\"status\":\"SUCCESS\",\"activeColor\":\"stable\",\"targetHostUrl\":\"" + url + "\",\"logs\":[\"ok\"]}";
    }

    @Test
    void 한_번_빌드해_GCP에_DB와_마이그레이션으로_먼저_배포하고_AWS에는_릴레이로_GCP_DB를_따라가게_배포한다() {
        gcp.expect(requestTo("http://gcp-cicd/api/deployments")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"appName":"blog","imageUrl":"ar/blog:t","database":"postgres",
                         "migrations":{"01_create_posts.yaml":"operations: []"},"aliases":["blog-gcp.lilycloud.kr"]}"""))
                .andRespond(withSuccess(ok("https://blog.lilycloud.kr"), MediaType.APPLICATION_JSON));
        aws.expect(requestTo("http://aws-cicd/api/deployments")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"appName":"blog","imageUrl":"ecr/blog:t","database":null,"followPgroll":true,
                         "databaseEnv":{"DATABASE_URL":"postgresql://u:p@db-relay-gcp.lily-builds.svc.cluster.local:5432/blog"},
                         "aliases":["blog-aws.lilycloud.kr"]}"""))
                .andRespond(withSuccess(ok("https://blog.lilycloud.kr"), MediaType.APPLICATION_JSON));

        Build build = service.start(multi("postgres"));

        assertThat(build.getStatus()).isEqualTo(Build.Status.SUCCEEDED);
        assertThat(build.getLogs()).contains("cloudProvider=GCP", BuildService.MULTI_CLOUD,
                "build: pushed ar/blog:t", "build: pushed ecr/blog:t",
                "multicloud: gcp deployed", "multicloud: aws deployed", "edge: multi");
        verify(edge).attachMulti("blog", BuildService.INITIAL_GCP_PERCENT);
        gcp.verify();
        aws.verify();
    }

    @Test
    void AWS_배포가_실패하면_GCP를_스키마까지_되돌리고_실패로_남긴다() {
        gcp.expect(requestTo("http://gcp-cicd/api/deployments"))
                .andRespond(withSuccess(ok("https://blog.lilycloud.kr"), MediaType.APPLICATION_JSON));
        aws.expect(requestTo("http://aws-cicd/api/deployments"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("{\"status\":\"FAILED\",\"message\":\"Ready 가 되지 않음\",\"logs\":[]}")
                        .contentType(MediaType.APPLICATION_JSON));
        gcp.expect(requestTo("http://gcp-cicd/api/deployments/blog/rollback")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"appOnly\":false}"))
                .andRespond(withSuccess("{\"status\":\"ROLLED_BACK\"}", MediaType.APPLICATION_JSON));

        Build build = service.start(multi("postgres"));

        assertThat(build.getStatus()).isEqualTo(Build.Status.FAILED);
        assertThat(build.getLogs()).contains("multicloud: aws failed, gcp rolled back");
        verify(edge, never()).attachMulti(anyString(), anyInt());
        gcp.verify();
    }

    @Test
    void MySQL은_빌드하지_않고_거절한다() {
        Build build = service.start(multi("mysql"));

        assertThat(build.getStatus()).isEqualTo(Build.Status.FAILED);
        verify(kaniko, never()).build(anyString(), any(), anyString(), any(), any(), anyList());
    }

    @Test
    void 내_PC_앱은_멀티클라우드로_받지_않는다() {
        AgentDeployService agents = new AgentDeployService(store, github, service, mock(AgentHub.class),
                new BuildServiceTest.SyncRunner(), null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> agents.start("abcdefabcdef", multi("postgres")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
