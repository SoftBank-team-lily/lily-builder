package com.lily.builder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 빌드할 커밋을 고정하고, 그 커밋의 마이그레이션 파일을 모은다.
 *
 * <p>브랜치는 빌드 도중에 움직일 수 있다. 커밋 SHA 를 먼저 정해서 Kaniko 이미지와 마이그레이션이 같은 커밋에서 나오게 한다.
 * 파일은 lily-cicd 가 배포 요청으로 받아 스키마를 옮기고, 롤백에 쓰려고 보관한다 (lily-cicd docs/schema-migration.md).
 *
 * <p>GitHub API 는 빌드당 2번 부른다 (커밋, 트리). 파일 내용은 rate limit 에 들어가지 않는 raw 주소에서 받는다.
 * 토큰이 없으면 IP 당 시간당 60번이라 약 30빌드까지다.
 */
@Component
public class GitHubSource {

    static final String DEFAULT_MIGRATIONS_PATH = "src/main/resources/db/migration";
    /** lily-cicd 가 한 ConfigMap 에 보관할 수 있는 크기 */
    static final int MAX_TOTAL_BYTES = 900 * 1024;
    private static final Pattern MIGRATION_FILE = Pattern.compile("[VUR][^/]*__[^/]*\\.sql");

    private final RestClient http;
    private final String apiBase;
    private final String rawBase;

    @Autowired
    public GitHubSource() {
        this(RestClient.builder().requestFactory(timeouts()), "https://api.github.com", "https://raw.githubusercontent.com");
    }

    GitHubSource(RestClient.Builder http, String apiBase, String rawBase) {
        this.http = http.defaultHeader("X-GitHub-Api-Version", "2022-11-28").build();
        this.apiBase = apiBase;
        this.rawBase = rawBase;
    }

    /** 브랜치 끝 커밋 SHA */
    public String resolveCommit(BuildRequest request) {
        try {
            String sha = get(request, URI.create(apiBase + "/repos/" + repo(request) + "/commits/" + request.branchOrDefault()))
                    .header(HttpHeaders.ACCEPT, "application/vnd.github.sha")
                    .retrieve()
                    .body(String.class);
            if (sha == null || !sha.trim().matches("[0-9a-f]{40}")) {
                throw new IllegalStateException("커밋 SHA 를 읽지 못했다: " + sha);
            }
            return sha.trim();
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("브랜치 " + request.branchOrDefault() + " 를 찾지 못했다 (GitHub "
                    + e.getStatusCode().value() + "). private 레포면 토큰이 필요하다", e);
        }
    }

    /**
     * {rootDir}/{migrationsPath} 아래의 V, U, R 파일 (하위 폴더 포함, 파일명 → 내용).
     * 폴더가 없거나 {@code migrate=false} 면 비어 있다. 그러면 lily-cicd 는 이전처럼 앱의 Flyway 에 맡긴다.
     */
    public Map<String, String> migrations(BuildRequest request, String commit) {
        if (!request.migrateOrDefault()) {
            return Map.of();
        }
        String prefix = folder(request) + "/";
        Tree tree;
        try {
            tree = get(request, URI.create(apiBase + "/repos/" + repo(request) + "/git/trees/" + commit + "?recursive=1"))
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .body(Tree.class);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("레포 파일 목록을 읽지 못했다 (GitHub " + e.getStatusCode().value() + ")", e);
        }
        Map<String, String> files = new TreeMap<>();
        long bytes = 0;
        for (Tree.Entry entry : tree == null || tree.tree() == null ? List.<Tree.Entry>of() : tree.tree()) {
            if (!"blob".equals(entry.type()) || !entry.path().startsWith(prefix)) {
                continue;
            }
            String name = entry.path().substring(entry.path().lastIndexOf('/') + 1);
            if (!MIGRATION_FILE.matcher(name).matches()) {
                continue;
            }
            String sql = get(request, URI.create(rawBase + "/" + repo(request) + "/" + commit + "/" + entry.path()))
                    .retrieve()
                    .body(String.class);
            sql = sql == null ? "" : sql;
            if (files.put(name, sql) != null) {
                throw new IllegalStateException("마이그레이션 파일명이 겹친다 (하위 폴더): " + name);
            }
            bytes += sql.getBytes(StandardCharsets.UTF_8).length;
        }
        if (bytes > MAX_TOTAL_BYTES) {
            throw new IllegalStateException("마이그레이션 합계 " + bytes / 1024 + "KiB 가 " + MAX_TOTAL_BYTES / 1024 + "KiB 를 넘는다");
        }
        return files;
    }

    /**
     * 커밋의 파일 하나 ({rootDir}/{path}). 없으면 null. raw 주소라 API rate limit 에 들어가지 않는다.
     */
    public String file(BuildRequest request, String commit, String path) {
        String root = request.rootDir() == null ? "" : request.rootDir();
        String joined = (root + "/" + path).replaceAll("/+", "/").replaceAll("^/", "");
        try {
            return get(request, URI.create(rawBase + "/" + repo(request) + "/" + commit + "/" + joined))
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /** 레포 기준 마이그레이션 폴더. 예: {@code backend/src/main/resources/db/migration} */
    static String folder(BuildRequest request) {
        String path = request.migrationsPath() == null || request.migrationsPath().isBlank()
                ? DEFAULT_MIGRATIONS_PATH : request.migrationsPath();
        String root = request.rootDir() == null ? "" : request.rootDir();
        String joined = (root + "/" + path).replaceAll("/+", "/");
        return joined.replaceAll("^/|/$", "");
    }

    /** {@code https://github.com/org/repo(.git)} → {@code org/repo} */
    static String repo(BuildRequest request) {
        return request.repoUrl().replaceFirst("^https://github\\.com/", "").replaceFirst("/$", "").replaceFirst("\\.git$", "");
    }

    private RestClient.RequestHeadersSpec<?> get(BuildRequest request, URI uri) {
        RestClient.RequestHeadersSpec<?> spec = http.get().uri(uri);
        if (request.token() != null && !request.token().isBlank()) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + request.token());
        }
        return spec;
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(20));
        return factory;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Tree(List<Entry> tree, boolean truncated) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Entry(String path, String type) {}
    }
}
