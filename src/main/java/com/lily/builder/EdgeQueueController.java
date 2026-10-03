package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 온프레미스 앱의 엣지 쓰기 큐 ({@link EdgeQueue}). 클러스터 안(lily-frontend 서버)에서만 부른다.
 *
 * <pre>
 * GET /api/apps/{app}/write-queue   {routed, paths, counts:{queued,sent,failed}, items:[...]}
 * PUT /api/apps/{app}/write-queue   {"paths": ["/posts", ...]} 등록 경로를 바꾸고 GET 과 같은 값을 돌려준다
 * </pre>
 *
 * routed: 공개 주소에 엣지 Worker 라우트가 있는가. 없으면 경로를 등록해도 요청이 Worker 를 거치지 않는다. 확인하지 못하면 null.
 * 큐가 꺼져 있으면(PLATFORM_EDGE_QUEUE_KEY 없음) 404.
 */
@RestController
@RequestMapping("/api/apps/{appName}/write-queue")
public class EdgeQueueController {

    private final EdgeQueue queue;
    private final EdgeWorker edge;

    public EdgeQueueController(EdgeQueue queue, EdgeWorker edge) {
        this.queue = queue;
        this.edge = edge;
    }

    @GetMapping
    public ResponseEntity<?> state(@PathVariable String appName) {
        if (!queue.enabled() || !edge.enabled()) {
            return off();
        }
        return ResponseEntity.ok(withRoute(appName, queue.state(appName)));
    }

    @PutMapping
    public ResponseEntity<?> configure(@PathVariable String appName, @Valid @RequestBody PathsRequest request) {
        if (!queue.enabled() || !edge.enabled()) {
            return off();
        }
        return ResponseEntity.ok(withRoute(appName, queue.configure(appName, request.paths())));
    }

    /** 경로는 / 로 시작하고 쿼리·공백이 없다 (queue.js 의 validPaths 와 같다) */
    public record PathsRequest(
            @NotNull @Size(max = 20) List<@NotNull @Pattern(regexp = "/[^?#\\s]{0,199}") String> paths) {
    }

    private JsonNode withRoute(String app, JsonNode state) {
        ObjectNode out = state.deepCopy();
        Boolean routed;
        try {
            routed = edge.routed(app);
        } catch (RuntimeException e) {
            routed = null;
        }
        if (routed == null) {
            out.putNull("routed");
        } else {
            out.put("routed", routed);
        }
        return out;
    }

    private static ResponseEntity<Map<String, String>> off() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "엣지 쓰기 큐가 꺼져 있다"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> unavailable(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", e.getMessage()));
    }
}
