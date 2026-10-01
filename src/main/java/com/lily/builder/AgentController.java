package com.lily.builder;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 온프레미스 에이전트 관리. 클러스터 안(lily-frontend 서버)에서만 부른다.
 * 외부에는 소켓 경로({@link AgentSocket#PATH})만 열려 있다.
 *
 * <pre>
 * POST /api/agents                 토큰 발급 → {key, token}. token 은 이때만 보인다
 * GET  /api/agents/{key}           연결 여부, agentId, DB 터널 여부
 * POST /api/agents/{key}/builds    이 에이전트로 배포 (본문은 POST /api/builds 와 같다) → Build
 * </pre>
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentTokens tokens;
    private final AgentHub hub;
    private final AgentDeployService deploys;

    public AgentController(AgentTokens tokens, AgentHub hub, AgentDeployService deploys) {
        this.tokens = tokens;
        this.hub = hub;
        this.deploys = deploys;
    }

    @PostMapping
    public ResponseEntity<?> issue() {
        if (!tokens.enabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "AGENT_TOKEN_SECRET 이 설정되지 않았다"));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(tokens.issue());
    }

    // key 형식을 경로에 건다. 그러지 않으면 GET /api/agents/connect (소켓 핸드셰이크)를 이 메서드가 먼저 가져간다
    @GetMapping("/{key:[0-9a-f]{12}}")
    public AgentHub.Status status(@PathVariable String key) {
        return hub.status(key);
    }

    @PostMapping("/{key:[0-9a-f]{12}}/builds")
    public ResponseEntity<Build> build(@PathVariable String key, @Valid @RequestBody BuildRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(deploys.start(key, request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
