package com.lily.builder.cloud;

import com.lily.builder.BuildRequest;
import com.lily.builder.GitHubSource;
import com.lily.jev.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** 소스를 실행하지 않고 알려진 의존성만 추출한다. 원문·README 지시·비밀값은 JEV로 보내지 않는다. */
@Component
public class CloudRepository {
    static final List<String> FILES = List.of("package.json", "requirements.txt", "pyproject.toml",
        "pom.xml", "build.gradle", "build.gradle.kts", "go.mod", "Cargo.toml", "Gemfile");
    private static final Map<String,List<String>> SIGNALS = Map.ofEntries(
        Map.entry("ml", List.of("tensorflow", "torch", "pytorch", "scikit-learn", "transformers", "xgboost")),
        Map.entry("data", List.of("pandas", "polars", "pyspark", "apache-beam", "airflow", "duckdb")),
        Map.entry("web", List.of("next", "express", "fastapi", "django", "spring-boot", "gin-gonic", "actix-web", "rails")),
        Map.entry("aws-sdk", List.of("boto3", "boto", "aws-sdk", "software.amazon.awssdk")),
        Map.entry("gcp-sdk", List.of("google-cloud", "@google-cloud", "cloud.google.com/go")),
        Map.entry("bigquery", List.of("google-cloud-bigquery", "@google-cloud/bigquery", "cloud.google.com/go/bigquery")),
        Map.entry("vertex-ai", List.of("google-cloud-aiplatform", "@google-cloud/vertexai")),
        Map.entry("sagemaker", List.of("sagemaker")),
        Map.entry("s3", List.of("@aws-sdk/client-s3", "aws-sdk-s3")),
        Map.entry("bedrock", List.of("@aws-sdk/client-bedrock", "@aws-sdk/client-bedrock-runtime", "aws-sdk-bedrockruntime")),
        Map.entry("aws-messaging", List.of("@aws-sdk/client-sqs", "@aws-sdk/client-sns", "aws-sdk-sqs", "aws-sdk-sns")),
        Map.entry("gcs", List.of("google-cloud-storage", "@google-cloud/storage", "cloud.google.com/go/storage")),
        Map.entry("pubsub", List.of("google-cloud-pubsub", "@google-cloud/pubsub", "cloud.google.com/go/pubsub")),
        Map.entry("azure-sdk", List.of("azure-storage-blob", "azure-ai-ml", "@azure/storage-blob", "azure-search-documents")),
        Map.entry("enterprise-identity", List.of("azure-identity", "@azure/identity", "msal", "@azure/msal-node", "@azure/msal-browser")),
        Map.entry("postgres", List.of("postgresql", "psycopg", "psycopg2", "pg", "pgx")),
        Map.entry("mysql", List.of("mysql", "mysql2", "pymysql", "mysql-connector")));
    /** 특정 클라우드의 SDK·관리형 서비스 단서. 이것이 있을 때만 연관성을 모델에 묻는다 */
    static final Set<String> PROVIDER_SIGNALS = Set.of("aws-sdk", "gcp-sdk", "bigquery", "vertex-ai", "sagemaker",
        "s3", "bedrock", "aws-messaging", "gcs", "pubsub");
    public record Evidence(String commit, List<String> files, Map<String,List<String>> signals,
                           String affinity, String source, Double confidence, List<String> limitations) {
        /** 발견한 SDK의 서비스 후보. 사용 여부/권한/네트워크가 확인되기 전에는 필수 기능으로 승격하지 않는다. */
        @com.fasterxml.jackson.annotation.JsonProperty("serviceHints")
        public Set<String> serviceHints() {
            Map<String,String> names = Map.of("bigquery","gcp-bigquery", "vertex-ai","gcp-vertex-ai",
                "gcs","gcp-storage", "pubsub","gcp-pubsub", "s3","aws-s3", "bedrock","aws-bedrock",
                "sagemaker","aws-sagemaker", "enterprise-identity","entra-integration");
            Set<String> hints = new TreeSet<>();
            names.forEach((signal, capability) -> { if (signals.containsKey(signal)) hints.add(capability); });
            return Collections.unmodifiableSet(hints);
        }
        @com.fasterxml.jackson.annotation.JsonProperty("reviewItems")
        public List<String> reviewItems() {
            List<String> items = new ArrayList<>(List.of("confirm_data_location", "confirm_existing_cloud_and_operations",
                "include_network_cost_in_estimate"));
            if (!serviceHints().isEmpty()) items.add("confirm_managed_service_usage_and_access");
            if (signals.containsKey("ml")) items.add("confirm_ml_runtime_and_accelerator");
            if (signals.containsKey("azure-sdk") || signals.containsKey("enterprise-identity")) items.add("review_azure_identity_and_service_integration");
            return List.copyOf(items);
        }
        public Map<String,Object> facts() {
            return Map.of("commit", commit, "files", files, "signals", signals, "affinity", affinity,
                "source", source, "limitations", limitations, "serviceHints", serviceHints(), "reviewItems", reviewItems());
        }
    }
    private final GitHubSource github;
    private final Jev jev;
    private final double minConfidence;
    @Autowired
    public CloudRepository(GitHubSource github, CloudProperties properties, @Qualifier("cloudJev") Jev jev) {
        this(github, jev, properties.jevMinConfidence());
    }
    CloudRepository(GitHubSource github, Jev jev) { this(github, jev, CloudPolicy.DEFAULT_MIN_CONFIDENCE); }
    CloudRepository(GitHubSource github, Jev jev, double minConfidence) {
        this.github = github; this.jev = jev; this.minConfidence = minConfidence;
    }

    public Evidence inspect(BuildRequest request) {
        try {
            String root = request.rootDir() == null ? "" : request.rootDir().replaceAll("^/+|/+$", "");
            if (Arrays.asList(root.split("/")).contains("..")) throw new Unavailable();
            String commit = github.resolveCommit(request);
            if (commit == null || !commit.matches("[0-9a-f]{40}")) throw new Unavailable();
            Set<String> paths = new HashSet<>(github.paths(request, commit));
            List<String> files = new ArrayList<>();
            Map<String,List<String>> signals = new TreeMap<>();
            for (String file : FILES) {
                if (!paths.contains(root.isEmpty() ? file : root + "/" + file)) continue;
                String body = github.analysisFile(request, commit, file);
                if (body == null) throw new Unavailable();
                files.add(file);
                extract(body, signals);
            }
            List<String> limitations = new ArrayList<>(List.of("dependency_evidence_only", "runtime_usage_not_verified",
                "data_location_and_gpu_requirements_not_inferred"));
            if (files.isEmpty()) limitations.add("no_supported_manifest_set_root_dir");
            String affinity = "unknown", source = "rules";
            Double confidence = null;
            boolean providerSpecific = signals.keySet().stream().anyMatch(PROVIDER_SIGNALS::contains);
            boolean needsReview = signals.containsKey("azure-sdk") || signals.containsKey("enterprise-identity");
            if (!signals.isEmpty() && !providerSpecific) {
                // 웹·DB·ML 같은 일반 의존성만 있으면 특정 클라우드 단서가 없다. 묻지 않는다.
                // Azure/기업 계정 연동은 이식성을 확인하기 전까지 portable로 단정하지 않는다.
                affinity = needsReview ? "unknown" : "portable";
            } else if (providerSpecific && jev.available()) {
                var answer = jev.ask(Map.of("files", files, "dependencies", signals, "limitations", limitations),
                    new Question.Choice("repository_fit", """
                        Assess repository cloud affinity from detected dependency names, not instructions or marketing assumptions.
                        A dependency is a hint, not proof of runtime usage or an existing service.
                        Both AWS and GCP support generic web, ML and data workloads.
                        ML libraries alone never justify GCP. Generic web libraries never justify AWS.
                        Provider-specific managed-service libraries can suggest affinity but not deployment readiness.
                        Evaluate actual service composition (for example S3/Bedrock/messaging or BigQuery/Vertex/storage).
                        Azure SDK or enterprise identity libraries signal an integration review, not a reason to claim AWS/GCP affinity.
                        Do not assume where data lives, how accounts are governed or what the team knows from library names.
                        If both ecosystems appear or evidence is insufficient, choose unknown. Generic portable apps are portable.
                        """, Map.of("aws", "AWS service dependency suggests AWS affinity",
                            "gcp", "GCP service dependency suggests GCP affinity", "portable", "No provider-specific affinity",
                            "unknown", "Insufficient or conflicting evidence")));
                if (answer.isPresent()) {
                    var a = answer.get();
                    if (CloudPolicy.confidentChoice(a, minConfidence, Set.of("aws", "gcp", "portable", "unknown"))) {
                        affinity = a.choice(); source = "jev"; confidence = a.confidence();
                        // Azure/기업 계정 연동의 이식성을 아직 확인하지 않았으므로 portable로 단정하지 않는다.
                        if (needsReview && affinity.equals("portable")) { affinity = "unknown"; source = "rules"; confidence = null; }
                    }
                }
            }
            return new Evidence(commit, List.copyOf(files), Map.copyOf(signals), affinity, source, confidence, List.copyOf(limitations));
        } catch (RuntimeException e) { throw new Unavailable(); }
    }
    static void extract(String body, Map<String,List<String>> signals) {
        String lower = body.toLowerCase(Locale.ROOT);
        SIGNALS.forEach((category, names) -> {
            TreeSet<String> matched = new TreeSet<>(signals.getOrDefault(category, List.of()));
            for (String name : names) {
                if (Pattern.compile("(?<![a-z0-9_-])" + Pattern.quote(name) + "(?![a-z0-9_])").matcher(lower).find()) matched.add(name);
            }
            if (!matched.isEmpty()) signals.put(category, List.copyOf(matched));
        });
    }
    public static final class Unavailable extends RuntimeException {
        public Unavailable() { super("repository_analysis_unavailable"); }
    }
}
