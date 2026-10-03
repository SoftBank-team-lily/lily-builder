package com.lily.builder.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.lily.builder.BuilderProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.net.URI;
import java.util.*;

/** 별도 pk(String) 테이블. 운영 IAM/테이블은 인프라에서 준비한다. 연결 불가 시 배포하지 않는다. */
@Component
public class DynamoCloudState implements CloudState, AutoCloseable {
    private final DynamoDbClient db;
    private final String table;
    private final ObjectMapper json;
    @Autowired
    public DynamoCloudState(BuilderProperties props, ObjectMapper json, @Value("${lily.cloud.state-table:}") String table) {
        this.table = table;
        this.json = json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        if (table.isBlank()) { db = null; return; }
        var config = props.dynamodb();
        var builder = DynamoDbClient.builder().region(Region.of(config.region()));
        if (config.endpoint() != null && !config.endpoint().isBlank())
            builder.endpointOverride(URI.create(config.endpoint())).credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("local","local")));
        db = builder.build();
    }
    DynamoCloudState(DynamoDbClient db, ObjectMapper json, String table) {
        this.db=db; this.table=table; this.json=json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
    private void ready() { if (db == null) throw new Unavailable(); }
    @Override public void savePlan(Plan plan) {
        ready();
        try { db.putItem(r -> r.tableName(table).item(item("PLAN#"+plan.id(),plan))
            .conditionExpression("attribute_not_exists(pk)")); }
        catch (RuntimeException e) { throw new Unavailable(); }
    }
    @Override public Optional<Plan> plan(String id) { return get("PLAN#"+id,Plan.class); }
    @Override public Optional<Run> request(String id) { return get("REQUEST#"+id,Run.class); }
    @Override public Optional<Run> app(String name) { return get("APP#"+name,Run.class); }
    private <T> Optional<T> get(String key, Class<T> type) {
        ready();
        try {
            var item=db.getItem(r -> r.tableName(table).key(Map.of("pk",s(key))).consistentRead(true)).item();
            return item.isEmpty() ? Optional.empty() : Optional.of(json.readValue(item.get("body").s(),type));
        } catch (Exception e) { throw new Unavailable(); }
    }
    @Override public boolean claim(Run next, Run previous) {
        return transact(put("REQUEST#"+next.requestId(),next,null),put("APP#"+next.appName(),next,previous));
    }
    @Override public boolean update(Run previous, Run next) {
        return transact(put("REQUEST#"+next.requestId(),next,previous),put("APP#"+next.appName(),next,previous));
    }
    private TransactWriteItem put(String key, Run next, Run previous) {
        var put=Put.builder().tableName(table).item(item(key,next));
        if (previous == null) put.conditionExpression("attribute_not_exists(pk)");
        else put.conditionExpression("requestId = :id AND #status = :status")
            .expressionAttributeNames(Map.of("#status","status"))
            .expressionAttributeValues(Map.of(":id",s(previous.requestId()),":status",s(previous.status())));
        return TransactWriteItem.builder().put(put.build()).build();
    }
    private boolean transact(TransactWriteItem request, TransactWriteItem app) {
        ready();
        try { db.transactWriteItems(r -> r.transactItems(request,app)); return true; }
        catch (TransactionCanceledException e) {
            if (e.cancellationReasons().stream().anyMatch(r -> "ConditionalCheckFailed".equals(r.code()))) return false;
            throw new Unavailable();
        } catch (RuntimeException e) { throw new Unavailable(); }
    }
    private Map<String,AttributeValue> item(String key, Object value) {
        try {
            String body=json.writeValueAsString(value);
            if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 350000) throw new Unavailable();
            Map<String,AttributeValue> item=new HashMap<>(Map.of("pk",s(key),"body",s(body)));
            if (value instanceof Run run) { item.put("requestId",s(run.requestId())); item.put("status",s(run.status())); }
            // TTL 삭제는 지연될 수 있으므로 API도 expiresAt을 검사한다. 실행/앱 기록에는 TTL을 넣지 않는다.
            if (value instanceof Plan plan) item.put("expiresAt",AttributeValue.builder().n(Long.toString(plan.expiresAt().getEpochSecond())).build());
            return item;
        } catch (Exception e) { throw new Unavailable(); }
    }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    @Override public void close() { if (db != null) db.close(); }
}
