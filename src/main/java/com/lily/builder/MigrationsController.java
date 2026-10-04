package com.lily.builder;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 옮긴 적 있는 앱마다 가장 최근 옮기기 기록 ({@link AppMigration#latestAll}). 로그는 싣지 않는다 */
@RestController
public class MigrationsController {

    private final AppMigration migration;

    public MigrationsController(AppMigration migration) {
        this.migration = migration;
    }

    @GetMapping("/api/migrations")
    public List<AppMigration.View> migrations() {
        return migration.latestAll().stream()
                .map(v -> new AppMigration.View(v.appName(), v.id(), v.from(), v.to(), v.state(), v.step(),
                        v.downtimeMs(), v.hybrid(), v.startedAt(), v.updatedAt(), List.of()))
                .toList();
    }
}
