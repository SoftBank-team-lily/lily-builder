package com.lily.builder;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 배포 폼 한 건.
 *
 * @param repoUrl       GitHub 주소. 예: {@code https://github.com/SoftBank-team-lily/lily-blog-sample}
 * @param branch        비우면 main
 * @param token         private 레포일 때만. GitHub Personal Access Token
 * @param rootDir       Dockerfile 이 있는 폴더. 비우면 레포 루트 (백엔드/프론트가 한 레포에 있을 때 지정)
 * @param appName       앱 이름. 도메인과 k3s 리소스 이름에 쓰인다 (lily-cicd appName 규칙)
 * @param targetPort    컨테이너 포트. 비우면 Dockerfile EXPOSE, 그것도 없으면 8080
 * @param database      DB 가 필요하면 postgres 또는 mysql. auto 면 레포의 드라이버로 정한다 (없으면 DB 없이)
 * @param readinessPath 비우면 lily-cicd 기본값 (/actuator/health/readiness). 레포에 Spring actuator 가 없으면 /
 * @param livenessPath  비우면 lily-cicd 기본값 (/actuator/health/liveness). 레포에 Spring actuator 가 없으면 /
 * @param env           앱에 넣을 환경변수
 * @param host          Ingress 호스트 전체. 비우면 lily-cicd 기본값 ({@code {appName}.{domain}}).
 *                      클라우드 버스팅에서 온프레미스 공개 주소로 들어온 요청을 그대로 받을 때 넣는다
 * @param standby       true 면 배포가 끝난 뒤 레플리카를 0 으로 내려 대기시킨다 (클라우드 버스팅)
 * @param migrationsPath 마이그레이션 폴더 (rootDir 기준). 비우면 src/main/resources/db/migration
 * @param migrate       false 면 마이그레이션을 플랫폼에 넘기지 않고 앱의 Flyway 에 맡긴다. 비우면 true
 * @param canaryPath    canary 판정 때 새 버전과 이전 버전에 보낼 경로. 비우면 readiness 경로 (lily-cicd docs/canary-analysis.md)
 */
public record BuildRequest(
        @NotBlank @Pattern(regexp = "https://github\\.com/[\\w.-]+/[\\w.-]+?(\\.git)?/?") String repoUrl,
        @Pattern(regexp = "[\\w./-]*") String branch,
        String token,
        @Pattern(regexp = "[\\w./-]*") String rootDir,
        @NotBlank @Size(max = 55) @Pattern(regexp = "[a-z0-9]([-a-z0-9]*[a-z0-9])?") String appName,
        @Min(1) @Max(65535) Integer targetPort,
        @Pattern(regexp = "postgres|mysql|auto|") String database,
        String readinessPath,
        String livenessPath,
        Map<String, String> env,
        @Pattern(regexp = "([a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+)?") String host,
        Boolean standby,
        @Pattern(regexp = "[\\w./-]*") String migrationsPath,
        Boolean migrate,
        @Pattern(regexp = "(/[!-~]*)?") String canaryPath) {

    public static final int DEFAULT_TARGET_PORT = 8080;

    /** canary 경로 기본값 */
    public BuildRequest(String repoUrl, String branch, String token, String rootDir, String appName,
                        Integer targetPort, String database, String readinessPath, String livenessPath,
                        Map<String, String> env, String host, Boolean standby, String migrationsPath,
                        Boolean migrate) {
        this(repoUrl, branch, token, rootDir, appName, targetPort, database, readinessPath, livenessPath,
                env, host, standby, migrationsPath, migrate, null);
    }

    /** 배포 폼 (호스트 지정, 대기 없이, 기본 마이그레이션 폴더) */
    public BuildRequest(String repoUrl, String branch, String token, String rootDir, String appName,
                        Integer targetPort, String database, String readinessPath, String livenessPath,
                        Map<String, String> env) {
        this(repoUrl, branch, token, rootDir, appName, targetPort, database, readinessPath, livenessPath,
                env, null, null, null, null, null);
    }

    /** 레포를 보고 DB 를 정한다 */
    public boolean autoDatabase() {
        return "auto".equals(database);
    }

    /** 포트·DB·헬스 경로 중 레포를 봐야 정해지는 값이 있다 ({@link AppDetector}) */
    public boolean needsDetection() {
        return targetPort == null || autoDatabase() || blank(readinessPath) || blank(livenessPath);
    }

    /** 추정한 값으로 채운 요청 */
    public BuildRequest withDetected(int port, String database, String readinessPath, String livenessPath) {
        return new BuildRequest(repoUrl, branch, token, rootDir, appName, port, database, readinessPath, livenessPath,
                env, host, standby, migrationsPath, migrate, canaryPath);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public int targetPortOrDefault() {
        return targetPort == null ? DEFAULT_TARGET_PORT : targetPort;
    }

    public boolean isStandby() {
        return Boolean.TRUE.equals(standby);
    }

    public boolean migrateOrDefault() {
        return migrate == null || migrate;
    }

    public String branchOrDefault() {
        return branch == null || branch.isBlank() ? "main" : branch;
    }

    /**
     * Kaniko 의 git 컨텍스트. 커밋을 주면 그 커밋으로 고정한다.
     * 예: {@code git://github.com/org/repo.git#refs/heads/main#0123abc...}
     */
    public String gitContext(String commit) {
        String path = repoUrl.replaceFirst("^https://", "").replaceFirst("/$", "");
        if (!path.endsWith(".git")) {
            path += ".git";
        }
        String context = "git://" + path + "#refs/heads/" + branchOrDefault();
        return commit == null || commit.isBlank() ? context : context + "#" + commit;
    }
}
