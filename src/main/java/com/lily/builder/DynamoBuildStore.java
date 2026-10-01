package com.lily.builder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 배포 이력 테이블 (파티션 키 pk 하나, pk = BUILD#{id}).
 * 목록은 Scan 으로 읽는다. 해커톤 규모라 충분하고, 커지면 앱별 GSI 로 바꾼다.
 */
@Component
public class DynamoBuildStore implements BuildStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DynamoBuildStore.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final String PREFIX = "BUILD#";
    /** 아이템 400KB 제한 안에 들어오도록 로그는 최근 것만 남긴다 */
    private static final int MAX_LOG_LINES = 200;

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoBuildStore(BuilderProperties props) {
        BuilderProperties.Dynamodb cfg = props.dynamodb();
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(cfg.region()));
        if (cfg.endpoint() != null && !cfg.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(cfg.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        }
        this.dynamo = builder.build();
        this.table = cfg.table();
        if (cfg.createTable()) {
            createTableIfMissing();
        }
    }

    @Override
    public void save(Build b) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", s(PREFIX + b.getId()));
        item.put("id", s(b.getId()));
        item.put("appName", s(b.getAppName()));
        item.put("repoUrl", s(b.getRepoUrl()));
        item.put("branch", s(b.getBranch()));
        item.put("status", s(b.getStatus().name()));
        item.put("createdAt", s(b.getCreatedAt().toString()));
        item.put("updatedAt", s(b.getUpdatedAt().toString()));
        putIfPresent(item, "rootDir", b.getRootDir());
        putIfPresent(item, "database", b.getDatabase());
        putIfPresent(item, "image", b.getImage());
        putIfPresent(item, "url", b.getUrl());
        if (b.getDiagnosis() != null) {
            try {
                item.put("diagnosis", s(JSON.writeValueAsString(b.getDiagnosis())));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.warn("diagnosis not saved: id={} message={}", b.getId(), e.getMessage());
            }
        }
        List<String> logs = b.getLogs();
        List<String> tail = logs.subList(Math.max(0, logs.size() - MAX_LOG_LINES), logs.size());
        item.put("logs", AttributeValue.fromL(tail.stream().map(DynamoBuildStore::s).toList()));
        dynamo.putItem(r -> r.tableName(table).item(item));
    }

    @Override
    public Optional<Build> find(String id) {
        Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(table)
                .key(Map.of("pk", s(PREFIX + id))).consistentRead(true)).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(fromItem(item));
    }

    @Override
    public List<Build> findAll() {
        return dynamo.scanPaginator(r -> r.tableName(table)).items().stream()
                .map(DynamoBuildStore::fromItem)
                .sorted(Comparator.comparing(Build::getCreatedAt).reversed())
                .toList();
    }

    private static Build fromItem(Map<String, AttributeValue> item) {
        Build build = new Build(
                item.get("id").s(),
                item.get("appName").s(),
                item.get("repoUrl").s(),
                item.get("branch").s(),
                optional(item, "rootDir"),
                optional(item, "database"),
                Instant.parse(item.get("createdAt").s()),
                Instant.parse(item.get("updatedAt").s()),
                Build.Status.valueOf(item.get("status").s()),
                optional(item, "image"),
                optional(item, "url"),
                item.containsKey("logs") ? item.get("logs").l().stream().map(AttributeValue::s).toList() : List.of());
        String diagnosis = optional(item, "diagnosis");
        if (diagnosis != null) {
            try {
                build.diagnosis(JSON.readValue(diagnosis, FailureDiagnoser.Diagnosis.class));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.warn("diagnosis unreadable: id={} message={}", build.getId(), e.getMessage());
            }
        }
        return build;
    }

    private void createTableIfMissing() {
        try {
            dynamo.createTable(r -> r.tableName(table)
                    .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                    .attributeDefinitions(AttributeDefinition.builder()
                            .attributeName("pk").attributeType(ScalarAttributeType.S).build())
                    .billingMode(BillingMode.PAY_PER_REQUEST));
            log.info("dynamodb table created: {}", table);
        } catch (ResourceInUseException ignored) {
            // 이미 있음
        }
    }

    private static void putIfPresent(Map<String, AttributeValue> item, String name, String value) {
        if (value != null) {
            item.put(name, s(value));
        }
    }

    private static String optional(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    @Override
    public void close() {
        dynamo.close();
    }
}
