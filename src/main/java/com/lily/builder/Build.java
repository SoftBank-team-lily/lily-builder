package com.lily.builder;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 빌드·배포 한 건. 배포 이력 한 줄이 된다.
 * 토큰은 담지 않는다 (저장하지 않는다).
 */
public final class Build {

    public enum Status { QUEUED, BUILDING, DEPLOYING, SUCCEEDED, FAILED }

    private final String id;
    private final String appName;
    private final String repoUrl;
    private final String branch;
    private final String rootDir;
    private final String database;
    private final Instant createdAt;
    private final List<String> logs;
    private volatile Instant updatedAt;
    private volatile Status status;
    private volatile String image;
    private volatile String url;

    Build(String id, BuildRequest request) {
        this(id, request.appName(), request.repoUrl(), request.branchOrDefault(), blankToNull(request.rootDir()),
                blankToNull(request.database()), Instant.now(), null, Status.QUEUED, null, null, List.of());
    }

    /** 저장소에서 읽어 올 때 */
    Build(String id, String appName, String repoUrl, String branch, String rootDir, String database,
          Instant createdAt, Instant updatedAt, Status status, String image, String url, List<String> logs) {
        this.id = id;
        this.appName = appName;
        this.repoUrl = repoUrl;
        this.branch = branch;
        this.rootDir = rootDir;
        this.database = database;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt == null ? createdAt : updatedAt;
        this.status = status;
        this.image = image;
        this.url = url;
        this.logs = new CopyOnWriteArrayList<>(logs);
    }

    void status(Status status, String log) {
        this.status = status;
        log(log);
    }

    void log(String line) {
        logs.add(line);
        updatedAt = Instant.now();
    }

    void image(String image) { this.image = image; }

    void url(String url) { this.url = url; }

    public String getId() { return id; }
    public String getAppName() { return appName; }
    public String getRepoUrl() { return repoUrl; }
    public String getBranch() { return branch; }
    public String getRootDir() { return rootDir; }
    public String getDatabase() { return database; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Status getStatus() { return status; }
    public String getImage() { return image; }
    public String getUrl() { return url; }
    public List<String> getLogs() { return List.copyOf(logs); }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
