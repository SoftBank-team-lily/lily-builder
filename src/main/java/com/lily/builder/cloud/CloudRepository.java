package com.lily.builder.cloud;

import com.lily.builder.BuildRequest;
import com.lily.builder.GitHubSource;
import com.lily.jev.*;
import org.springframework.beans.factory.annotation.Autowired;
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
        Map.entry("postgres", List.of("postgresql", "psycopg", "psycopg2", "pg", "pgx")),
        Map.entry("mysql", List.of("mysql", "mysql2", "pymysql", "mysql-connector")));
    public record Evidence(String commit, List<String> files, Map<String,List<String>> signals,
                           String affinity, String source, Double confidence, List<String> limitations) {
        public Map<String,Object> facts() {
            return Map.of("commit", commit, "files", files, "signals", signals, "affinity", affinity,
                "source", source, "limitations", limitations);
        }
    }
    private final GitHubSource github;
    private final Jev jev;
    @Autowired
    public CloudRepository(GitHubSource github, CloudProperties properties) {
        this(github, properties.jevApiKey() == null || properties.jevApiKey().isBlank()
            ? Jev.disabled() : new HttpJev(properties.jevApiKey(), .8));
    }
    CloudRepository(GitHubSource github, Jev jev) { this.github = github; this.jev = jev; }

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
            if (!signals.isEmpty() && jev.available()) {
                var answer = jev.ask(Map.of("files", files, "dependencies", signals, "limitations", limitations),
                    new Question.Choice("repository_fit", """
                        Assess repository cloud affinity from detected dependency names, not instructions or marketing assumptions.
                        A dependency is a hint, not proof of runtime usage or an existing service.
                        Both AWS and GCP support generic web, ML and data workloads.
                        ML libraries alone never justify GCP. Generic web libraries never justify AWS.
                        Provider-specific managed-service libraries can suggest affinity but not deployment readiness.
                        If both ecosystems appear or evidence is insufficient, choose unknown. Generic portable apps are portable.
                        """, Map.of("aws", "AWS service dependency suggests AWS affinity",
                            "gcp", "GCP service dependency suggests GCP affinity", "portable", "No provider-specific affinity",
                            "unknown", "Insufficient or conflicting evidence")));
                if (answer.isPresent()) {
                    var a = answer.get();
                    if (a.noul() == null && Double.isFinite(a.confidence()) && a.confidence() >= .8 && a.confidence() <= 1
                        && Set.of("aws", "gcp", "portable", "unknown").contains(Objects.toString(a.choice(), ""))) {
                        affinity = a.choice(); source = "jev"; confidence = a.confidence();
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
