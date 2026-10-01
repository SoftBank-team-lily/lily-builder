package com.lily.builder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 배포 전 DB 감지 한 건. 값의 뜻과 형식은 {@link BuildRequest} 와 같다.
 */
public record DetectRequest(
        @NotBlank @Pattern(regexp = "https://github\\.com/[\\w.-]+/[\\w.-]+?(\\.git)?/?") String repoUrl,
        @Pattern(regexp = "[\\w./-]*") String branch,
        String token,
        @Pattern(regexp = "[\\w./-]*") String rootDir) {
}
