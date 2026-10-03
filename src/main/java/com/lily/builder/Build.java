package com.lily.builder;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 빌드·배포 한 건. 배포 이력 한 줄이 된다.
 * 토큰은 담지 않는다 (저장하지 않는다).
 */
public final class Build {

    /**
     * ROLLED_BACK: canary 판정에서 떨어져 새 버전을 버렸다. 트래픽은 이전 버전 그대로.
     * CANCELLED: 사용자가 배포 도중 취소했다. 새 버전은 띄우지 않았고 트래픽은 이전 버전 그대로
     */
    public enum Status { QUEUED, BUILDING, DEPLOYING, SUCCEEDED, FAILED, ROLLED_BACK, CANCELLED }

    /**
     * 배포 화면의 여섯 단계. 로그로 정한다 (저장 형식을 바꾸지 않으려고).
     * 첫 배포처럼 canary 판정을 건너뛰면 2 에서 5 로 넘어간다.
     */
    public static final List<String> STAGES = List.of(
            "레포 확인", "빌드", "새 버전 띄우기", "트래픽 10%로 새 버전 내보내기", "에러율·응답 시간 판정", "트래픽 100%로 전환");

    private final String id;
    private final String appName;
    private final String repoUrl;
    private final String branch;
    private final String rootDir;
    /** 요청이 auto 면 레포를 본 뒤 정해진다 */
    private volatile String database;
    private final Instant createdAt;
    private final List<String> logs;
    private volatile Instant updatedAt;
    private volatile Status status;
    private volatile String image;
    private volatile String url;
    /** 이미지를 만든 커밋. 없으면 null */
    private volatile String commit;
    /** 실패했을 때 원인과 고칠 방법 ({@link FailureDiagnoser}). 그 외 null */
    private volatile FailureDiagnoser.Diagnosis diagnosis;

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

    void commit(String commit) { this.commit = commit; }

    void database(String database) { this.database = database; }

    void diagnosis(FailureDiagnoser.Diagnosis diagnosis) { this.diagnosis = diagnosis; }

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
    public String getCommit() { return commit; }
    public FailureDiagnoser.Diagnosis getDiagnosis() { return diagnosis; }
    public List<String> getLogs() { return List.copyOf(logs); }

    /** 0~5. {@link #STAGES} 의 번호 */
    public int getStage() {
        int stage = 0;
        for (String line : logs) {
            stage = Math.max(stage, stageOf(line));
        }
        if (status == Status.SUCCEEDED) {
            return STAGES.size() - 1;
        }
        return stage;
    }

    public String getStageName() {
        return STAGES.get(getStage());
    }

    /** canary 판정 결과 한 줄 (PASS / FAIL / skipped). 판정 전이면 null */
    public String getCanary() {
        String result = null;
        for (String line : logs) {
            int at = line.indexOf("canary: ");
            if (at >= 0 && (line.contains("canary: PASS") || line.contains("canary: FAIL")
                    || line.contains("canary: skipped"))) {
                result = line.substring(at);
            }
        }
        return result;
    }

    private static int stageOf(String line) {
        // 온프레미스(AgentDeployService): 에이전트가 보낸 단계
        if (line.startsWith("agent: BUILDING")) {
            return 1;
        }
        if (line.startsWith("agent: STARTING") || line.startsWith("agent: HEALTH")) {
            return 2;
        }
        if (line.startsWith("agent: JUDGING")) {
            return 4;
        }
        if (line.startsWith("agent: SWITCHING")) {
            return 5;
        }
        if (line.startsWith("build: pushed") || line.startsWith("deploy: ")) {
            return 2;
        }
        if (line.startsWith("build: ")) {
            return 1;
        }
        if (line.startsWith("progress: canary-traffic")) {
            return 3;
        }
        if (line.startsWith("progress: canary-analysis") && !line.contains("canary: skipped")) {
            return 4;
        }
        if (line.startsWith("progress: service") || line.startsWith("progress: router")
                || line.startsWith("progress: monitor") || line.startsWith("progress: scale-down")) {
            return 5;
        }
        return 0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
