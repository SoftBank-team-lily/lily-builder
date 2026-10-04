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
import java.util.regex.Pattern;

/**
 * 클라우드 전용 앱을 다른 클라우드로 옮긴다 ({@link AppMigration}).
 * 시작은 202 와 옮기기 기록, 진행은 GET. 끝나면 HOLD 에서 되돌리거나(rollback) 원본을 정리한다(finalize).
 */
@RestController
@RequestMapping("/api/apps/{appName}/migrate")
public class MigrateController {

    private static final Pattern APP_NAME = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");

    private final AppMigration migration;

    public MigrateController(AppMigration migration) {
        this.migration = migration;
    }

    /** @param request 이 앱의 평소 배포 요청에 cloudProvider 만 옮길 클라우드로 바꾼 것 */
    @PostMapping
    public ResponseEntity<?> start(@PathVariable String appName, @Valid @RequestBody BuildRequest request) {
        name(appName);
        migration.start(appName, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(migration.status(appName).orElseThrow());
    }

    @GetMapping
    public ResponseEntity<AppMigration.View> status(@PathVariable String appName) {
        name(appName);
        return migration.status(appName).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** 원본으로 되돌린다. 옮긴 뒤 새 클라우드에 쓴 데이터는 버리므로 {@code discardTargetWrites=true} 가 있어야 한다 */
    @PostMapping("/rollback")
    public ResponseEntity<AppMigration.View> rollback(@PathVariable String appName, @RequestBody(required = false) RollbackRequest request) {
        name(appName);
        if (request == null || !Boolean.TRUE.equals(request.discardTargetWrites())) {
            throw new IllegalArgumentException("옮긴 뒤 새 클라우드에 쓴 데이터는 버린다. discardTargetWrites=true 로 확인한다");
        }
        migration.rollback(appName);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(migration.status(appName).orElseThrow());
    }

    public record RollbackRequest(Boolean discardTargetWrites) {
    }

    /** 원본 클러스터의 앱(replicas 0)과 DB 를 지운다. 이후에는 되돌릴 수 없다 */
    @PostMapping("/finalize")
    public AppMigration.View finalizeMigration(@PathVariable String appName) {
        name(appName);
        migration.finalizeMigration(appName);
        return migration.status(appName).orElseThrow();
    }

    private static void name(String appName) {
        if (!APP_NAME.matcher(appName).matches()) {
            throw new IllegalArgumentException("앱 이름이 아니다");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("status", "REJECTED", "message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("status", "REJECTED", "message", String.valueOf(e.getMessage())));
    }
}
