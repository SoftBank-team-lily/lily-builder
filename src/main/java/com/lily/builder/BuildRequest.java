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
 * @param targetPort    컨테이너 포트
 * @param database      DB 가 필요하면 postgres 또는 mysql
 * @param readinessPath 비우면 lily-cicd 기본값 (/actuator/health/readiness)
 * @param livenessPath  비우면 lily-cicd 기본값 (/actuator/health/liveness)
 * @param env           앱에 넣을 환경변수
 */
public record BuildRequest(
        @NotBlank @Pattern(regexp = "https://github\\.com/[\\w.-]+/[\\w.-]+?(\\.git)?/?") String repoUrl,
        @Pattern(regexp = "[\\w./-]*") String branch,
        String token,
        @Pattern(regexp = "[\\w./-]*") String rootDir,
        @NotBlank @Size(max = 55) @Pattern(regexp = "[a-z0-9]([-a-z0-9]*[a-z0-9])?") String appName,
        @Min(1) @Max(65535) int targetPort,
        @Pattern(regexp = "postgres|mysql|") String database,
        String readinessPath,
        String livenessPath,
        Map<String, String> env) {

    public String branchOrDefault() {
        return branch == null || branch.isBlank() ? "main" : branch;
    }

    /** Kaniko 의 git 컨텍스트. 예: {@code git://github.com/org/repo.git#refs/heads/main} */
    public String gitContext() {
        String path = repoUrl.replaceFirst("^https://", "").replaceFirst("/$", "");
        if (!path.endsWith(".git")) {
            path += ".git";
        }
        return "git://" + path + "#refs/heads/" + branchOrDefault();
    }
}
