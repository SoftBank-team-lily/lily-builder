package com.lily.builder;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 빌드·배포 한 건의 진행 상태. 화면이 이걸 주기적으로 조회한다 */
public final class Build {

    public enum Status { QUEUED, BUILDING, DEPLOYING, SUCCEEDED, FAILED }

    private final String id;
    private final String appName;
    private final Instant createdAt = Instant.now();
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private volatile Status status = Status.QUEUED;
    private volatile String image;
    private volatile String url;

    Build(String id, String appName) {
        this.id = id;
        this.appName = appName;
    }

    void status(Status status, String log) {
        this.status = status;
        log(log);
    }

    void log(String line) {
        logs.add(line);
    }

    void image(String image) { this.image = image; }

    void url(String url) { this.url = url; }

    public String getId() { return id; }
    public String getAppName() { return appName; }
    public Instant getCreatedAt() { return createdAt; }
    public Status getStatus() { return status; }
    public String getImage() { return image; }
    public String getUrl() { return url; }
    public List<String> getLogs() { return List.copyOf(logs); }
}
