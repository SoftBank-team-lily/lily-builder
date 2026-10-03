package com.lily.builder.cloud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 작업 전용 DynamoDB Local에서만 실행한다. 운영 endpoint를 쓰지 않는다. */
@EnabledIfEnvironmentVariable(named="CLOUD_TEST_DYNAMODB", matches="http://(host\\.docker\\.internal|127\\.0\\.0\\.1):18081")
class DynamoCloudStateTest {
    @Test void plansSurviveNewInstancesAndConcurrentClaimsAllowOnlyOneWriter() throws Exception {
        var table="cloud-test-"+UUID.randomUUID();
        try (var db=DynamoDbClient.builder().region(Region.AP_NORTHEAST_2)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local","local")))
            .endpointOverride(URI.create(System.getenv("CLOUD_TEST_DYNAMODB"))).build()) {
            db.createTable(r -> r.tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build()));
            try {
                var first=new DynamoCloudState(db,CloudApiTest.JSON,table);
                var second=new DynamoCloudState(db,CloudApiTest.JSON,table);
                var fixture=new CloudPlansTest();
                var plan=fixture.plan("sensitive");
                first.savePlan(plan);
                assertThat(second.plan(plan.id()).orElseThrow()).isEqualTo(plan);
                var a=new CloudState.Run(UUID.randomUUID().toString(),plan.id(),"sample","gcp",null,"DISPATCHING",plan.repository().commit(),java.time.Instant.now());
                var b=new CloudState.Run(UUID.randomUUID().toString(),plan.id(),"sample","gcp",null,"DISPATCHING",plan.repository().commit(),java.time.Instant.now());
                try (var pool=Executors.newFixedThreadPool(2)) {
                    var one=pool.submit(() -> first.claim(a,null));
                    var two=pool.submit(() -> second.claim(b,null));
                    assertThat(one.get(5,TimeUnit.SECONDS) ^ two.get(5,TimeUnit.SECONDS)).isTrue();
                }
                var winner=second.app("sample").orElseThrow();
                var started=winner.with("worker-1","STARTED");
                assertThat(first.update(winner,started)).isTrue();
                assertThat(second.update(winner,winner.with("wrong","STARTED"))).isFalse();
                assertThat(second.request(winner.requestId()).orElseThrow()).isEqualTo(started);
                var ended=started.with("worker-1","FAILED");
                assertThat(second.update(started,ended)).isTrue();
                var next=new CloudState.Run(UUID.randomUUID().toString(),plan.id(),"sample","gcp",null,"DISPATCHING",plan.repository().commit(),java.time.Instant.now());
                assertThat(first.claim(next,ended)).isTrue();
            } finally { db.deleteTable(r -> r.tableName(table)); }
        }
    }
}
