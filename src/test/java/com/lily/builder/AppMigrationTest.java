package com.lily.builder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 클라우드 전용 앱을 AWS → GCP 로 옮기기. 실패하면 원본을 되살리고, 기존 배포 흐름은 막지 않는다 */
class AppMigrationTest {

    private static final String APP = "blog";
    private static final String RDS_URL = "postgresql://u:p@rds.example:5432/blog";
    private static final String CLOUDSQL_URL = "postgresql://u2:p2@127.0.0.1:15432/blog";

    private final InMemoryBuildStore store = new InMemoryBuildStore();
    private final BuildService builds = mock(BuildService.class);
    private final AgentDeployService deploys = mock(AgentDeployService.class);
    private final CicdClient aws = mock(CicdClient.class);
    private final CicdClient gcp = mock(CicdClient.class);
    private final ProvisionerClient awsDb = mock(ProvisionerClient.class);
    private final ProvisionerClient gcpDb = mock(ProvisionerClient.class);
    private final CloudClients clouds = mock(CloudClients.class);
    private final AppAddress addresses = mock(AppAddress.class);
    private final DatabaseCopy copy = mock(DatabaseCopy.class);
    private final TunnelCertificates certificates = mock(TunnelCertificates.class);
    private final AtomicInteger publicStatus = new AtomicInteger(200);
    private AppMigration migration;

    @BeforeEach
    void setUp() {
        migration = new AppMigration(store, builds, deploys, aws, awsDb, clouds, addresses, copy, certificates,
                new BuildServiceTest.SyncRunner(), url -> publicStatus.get(), Duration.ofMillis(50), Duration.ofMillis(5));
        store.save(succeeded(new Build("old00001", request()), "cloudProvider=AWS"));
        when(deploys.cloudProvider(APP)).thenReturn("AWS");
        when(clouds.profile()).thenReturn(new CloudProfiles("http://gcp-cicd", "gcp-reg", "gcp-pull",
                "http://gcp-prov", "token", "34.22.68.239", 80, "gcp.lilycloud.kr",
                "34.64.37.122", "lily-tunnel", "10.20.0.3", 5432, "10.10.0.2"));
        when(clouds.cicd()).thenReturn(gcp);
        when(clouds.provisioner()).thenReturn(gcpDb);
        when(clouds.origin()).thenReturn("gcp.lilycloud.kr");
        when(addresses.enabled()).thenReturn(true);
        when(addresses.host(APP)).thenReturn("blog.lilycloud.kr");
        when(addresses.state(APP)).thenReturn(new AppAddress.State("blog.lilycloud.kr", AppAddress.Home.CLOUD, "alb.example"));
        when(aws.release(APP)).thenReturn(ok("{\"activeSlot\":\"stable\",\"slots\":[{\"slot\":\"stable\",\"database\":\"postgres\"}]}"));
        when(aws.schema(APP)).thenReturn(ok("{\"engine\":\"flyway\"}"));
        when(gcp.release(APP)).thenReturn(ok("{\"activeSlot\":null,\"slots\":[]}"));
        when(awsDb.engine(APP)).thenReturn(Optional.of("postgres"));
        when(gcpDb.engine(APP)).thenReturn(Optional.empty());
        when(awsDb.existing(APP, null, null)).thenReturn(Optional.of(new ProvisionerClient.Connection("a", Map.of("DATABASE_URL", RDS_URL))));
        when(gcpDb.existing(APP, "127.0.0.1", DatabaseCopy.LOCAL_PORT))
                .thenReturn(Optional.of(new ProvisionerClient.Connection("g", Map.of("DATABASE_URL", CLOUDSQL_URL))));
        when(certificates.enabled()).thenReturn(true);
        when(certificates.jobKey(anyString(), anyInt())).thenReturn(new TunnelCertificates.JobKey("key", "cert"));
        when(aws.stop(APP)).thenReturn(ok("{}"));
        when(aws.start(APP)).thenReturn(ok("{}"));
        when(gcp.remove(eq(APP), anyBoolean())).thenReturn(ok("{}"));
        when(aws.remove(eq(APP), anyBoolean())).thenReturn(ok("{}"));
        when(aws.status(APP)).thenReturn(new CicdClient.AppStatus(APP, "default", "stable", 2, 2));
        when(copy.copy(anyString(), anyString(), anyString(), any())).thenReturn("copy: dumped 10 bytes\ncopy: restored, tables 2");
        when(builds.prepareImage(any(), anyString())).thenAnswer(call -> part(call.getArgument(1), "img:1", "abc"));
        when(builds.deployImage(any(), anyString(), anyString(), anyString())).thenAnswer(call -> part(call.getArgument(3), "img:1", "abc"));
    }

    @Test
    void 옮기면_이미지를_먼저_만들고_원본을_내린_뒤_DB_를_복사하고_GCP_로_배포한_다음_주소를_GCP_로_바꾼다() {
        Build record = migration.start(APP, request().withCloudProvider("GCP"));

        InOrder order = inOrder(builds, aws, copy, addresses);
        order.verify(builds).prepareImage(any(), eq(AppMigration.PART + record.getId()));
        order.verify(aws).stop(APP);
        order.verify(copy).copy(eq(record.getId()), eq(RDS_URL), eq(CLOUDSQL_URL), any());
        order.verify(builds).deployImage(any(), eq("img:1"), eq("abc"), eq(AppMigration.PART + record.getId()));
        order.verify(addresses).pointCloud(APP, "gcp.lilycloud.kr");
        verify(gcpDb).ensure(APP, "postgres", "127.0.0.1", DatabaseCopy.LOCAL_PORT);
        Build saved = store.find(record.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(Build.Status.SUCCEEDED);
        assertThat(AgentDeployService.lastCloudProvider(saved)).isEqualTo("GCP");
        assertThat(migration.status(APP).orElseThrow().state()).isEqualTo("HOLD");
        verify(aws, never()).remove(anyString(), anyBoolean());
    }

    @Test
    void DB_복사가_실패하면_원본을_다시_올리고_만든_GCP_DB_를_지우며_주소는_건드리지_않는다() {
        when(copy.copy(anyString(), anyString(), anyString(), any())).thenThrow(new IllegalStateException("DB 복사 실패"));

        Build record = migration.start(APP, request().withCloudProvider("GCP"));

        verify(aws).start(APP);
        verify(gcpDb).delete(APP);
        verify(addresses, never()).pointCloud(anyString(), any());
        verify(builds, never()).deployImage(any(), anyString(), anyString(), anyString());
        Build saved = store.find(record.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(Build.Status.FAILED);
        assertThat(AgentDeployService.lastCloudProvider(saved)).isEqualTo("AWS");
    }

    @Test
    void 공개_주소가_GCP_에서_응답하지_않으면_원본을_올리고_주소를_ALB_로_되돌리고_GCP_앱을_지운다() {
        publicStatus.set(503);

        Build record = migration.start(APP, request().withCloudProvider("GCP"));

        verify(addresses).pointCloud(APP, "gcp.lilycloud.kr");
        verify(addresses).pointCloud(APP, null);
        verify(aws).start(APP);
        verify(gcp).remove(APP, true);
        assertThat(store.find(record.getId()).orElseThrow().getStatus()).isEqualTo(Build.Status.FAILED);
    }

    @Test
    void pgroll_DB_와_내_PC_앱과_GCP_에_이미_있는_앱은_시작하지_않는다() {
        when(aws.schema(APP)).thenReturn(ok("{\"engine\":\"pgroll\"}"));
        assertThatThrownBy(() -> migration.start(APP, request().withCloudProvider("GCP"))).hasMessageContaining("pgroll");

        when(aws.schema(APP)).thenReturn(ok("{\"engine\":\"flyway\"}"));
        when(gcp.release(APP)).thenReturn(ok("{\"activeSlot\":\"stable\",\"slots\":[{\"slot\":\"stable\"}]}"));
        assertThatThrownBy(() -> migration.start(APP, request().withCloudProvider("GCP"))).hasMessageContaining("이미");

        when(gcp.release(APP)).thenReturn(ok("{\"activeSlot\":null,\"slots\":[]}"));
        Build agent = new Build("agent001", request());
        agent.log("queued: x target=onprem agent=abcdefabcdef");
        store.save(agent);
        assertThatThrownBy(() -> migration.start(APP, request().withCloudProvider("GCP"))).hasMessageContaining("내 PC");

        verify(aws, never()).stop(anyString());
        assertThat(migration.inProgress(APP)).isFalse();
    }

    @Test
    void 옮긴_앱에_옛_클라우드로_배포가_오면_막고_새_클라우드_배포와_다른_앱은_막지_않는다() {
        migration.start(APP, request().withCloudProvider("GCP"));

        assertThat(migration.conflict(request())).isPresent();
        assertThat(migration.conflict(request().withCloudProvider("GCP"))).isEmpty();
        assertThat(migration.conflict(new BuildRequest("https://github.com/org/blog", null, null, null, "other",
                null, "auto", null, null, Map.of()))).isEmpty();
    }

    @Test
    void 옮긴_적_없는_앱은_배포를_막지_않는다() {
        assertThat(migration.conflict(request())).isEmpty();
        assertThat(migration.inProgress(APP)).isFalse();
    }

    @Test
    void builder_가_복사_도중에_다시_뜨면_원본을_다시_올리고_GCP_DB_를_지운다() {
        Build record = new Build("mig00001", request().withCloudProvider("GCP"));
        record.log(AppMigration.RECORD + "from=AWS to=GCP");
        record.log("cloudProvider=AWS");
        record.log("migrate: database postgres");
        record.status(Build.Status.DEPLOYING, "migrate: started");
        record.log(AppMigration.STEP + "COPY");
        store.save(record);

        migration.resumeInterrupted();

        verify(aws).start(APP);
        verify(gcpDb).delete(APP);
        verify(addresses, never()).pointCloud(anyString(), any());
        assertThat(store.find("mig00001").orElseThrow().getStatus()).isEqualTo(Build.Status.FAILED);
    }

    @Test
    void HOLD_를_되돌리면_원본을_올리고_주소를_ALB_로_바꾼_뒤_GCP_앱을_지운다() {
        migration.start(APP, request().withCloudProvider("GCP"));

        Build record = migration.rollback(APP);

        InOrder order = inOrder(aws, addresses, gcp);
        order.verify(aws).start(APP);
        order.verify(addresses).pointCloud(APP, null);
        order.verify(gcp).remove(APP, true);
        assertThat(record.getStatus()).isEqualTo(Build.Status.ROLLED_BACK);
        assertThat(AgentDeployService.lastCloudProvider(record)).isEqualTo("AWS");
    }

    @Test
    void HOLD_를_정리하면_원본_앱과_DB_만_지우고_주소는_건드리지_않는다() {
        migration.start(APP, request().withCloudProvider("GCP"));

        migration.finalizeMigration(APP);

        verify(aws).remove(APP, true);
        verify(addresses).pointCloud(APP, "gcp.lilycloud.kr");
        verify(addresses, never()).pointCloud(APP, null);
        assertThat(migration.status(APP).orElseThrow().state()).isEqualTo("FINALIZED");
        assertThatThrownBy(() -> migration.rollback(APP)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 클라우드_판정은_한_빌드의_마지막_cloudProvider_줄을_본다() {
        Build build = new Build("b0000001", request());
        build.log("cloudProvider=AWS");
        build.log("cloudProvider=GCP");
        assertThat(AgentDeployService.lastCloudProvider(build)).isEqualTo("GCP");
        build.log("cloudProvider=AWS");
        assertThat(AgentDeployService.lastCloudProvider(build)).isEqualTo("AWS");
        assertThat(AgentDeployService.lastCloudProvider(new Build("b0000002", request()))).isEqualTo("AWS");
    }

    private Build part(String note, String image, String commit) {
        Build build = new Build(UUID.randomUUID().toString().substring(0, 8), request().withCloudProvider("GCP"));
        build.log("cloudProvider=GCP");
        build.log(note);
        build.image(image);
        build.commit(commit);
        build.status(Build.Status.SUCCEEDED, "done");
        store.save(build);
        return build;
    }

    private static Build succeeded(Build build, String line) {
        build.log(line);
        build.status(Build.Status.SUCCEEDED, "done");
        return build;
    }

    private static BuildRequest request() {
        return new BuildRequest("https://github.com/org/blog", null, null, null, APP, null, "postgres", null, null,
                Map.of("A", "1"));
    }

    private static CicdClient.Passthrough ok(String body) {
        return new CicdClient.Passthrough(200, body);
    }
}
