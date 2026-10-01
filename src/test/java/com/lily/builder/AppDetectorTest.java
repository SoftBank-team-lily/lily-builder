package com.lily.builder;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppDetectorTest {

    private static AppDetector.Result detect(Map<String, String> files) {
        return AppDetector.detect(files::get);
    }

    @Test
    void Spring_Gradle_앱은_Postgres_드라이버와_actuator_를_찾는다() {
        AppDetector.Result result = detect(Map.of(
                "Dockerfile", "FROM eclipse-temurin:21-jre\nEXPOSE 8080\nENTRYPOINT [\"java\",\"-jar\",\"app.jar\"]",
                "build.gradle", """
                        implementation 'org.springframework.boot:spring-boot-starter-actuator'
                        runtimeOnly 'org.postgresql:postgresql'"""));

        assertThat(result).isEqualTo(new AppDetector.Result(8080, "Dockerfile EXPOSE", "postgres",
                "build.gradle: org.postgresql", true));
    }

    @Test
    void Node_앱은_EXPOSE_포트와_mysql2_를_찾고_actuator_는_없다() {
        AppDetector.Result result = detect(Map.of(
                "Dockerfile", "FROM node:24\n  expose 3000/tcp\nCMD [\"node\",\"server.js\"]",
                "package.json", "{\"dependencies\":{\"express\":\"^5\",\"mysql2\":\"^3\"}}"));

        assertThat(result.port()).isEqualTo(3000);
        assertThat(result.database()).isEqualTo("mysql");
        assertThat(result.actuator()).isFalse();
    }

    @Test
    void Prisma_스키마의_provider_가_우선한다() {
        AppDetector.Result result = detect(Map.of(
                "package.json", "{\"dependencies\":{\"@prisma/client\":\"^6\",\"pg\":\"^8\"}}",
                "prisma/schema.prisma", """
                        datasource db {
                          provider = "mysql"
                          url      = env("DATABASE_URL")
                        }"""));

        assertThat(result.database()).isEqualTo("mysql");
        assertThat(result.databaseSource()).isEqualTo("prisma/schema.prisma provider mysql");
    }

    @Test
    void 드라이버가_없으면_DB_없음_EXPOSE_가_없으면_포트_없음() {
        AppDetector.Result result = detect(Map.of(
                "Dockerfile", "FROM nginx\nCOPY dist /usr/share/nginx/html",
                "package.json", "{\"dependencies\":{\"react\":\"^19\",\"pgadmin-ui\":\"1\"}}"));

        assertThat(result.port()).isNull();
        assertThat(result.database()).isNull();
        assertThat(result.actuator()).isFalse();
    }

    @Test
    void 파일이_하나도_없어도_된다() {
        assertThat(detect(Map.of())).isEqualTo(new AppDetector.Result(null, null, null, null, false));
    }

    @Test
    void 버전_카탈로그로_넣은_actuator_도_찾는다() {
        AppDetector.Result result = detect(Map.of(
                "build.gradle.kts", "implementation(libs.spring.boot.starter.actuator)"));

        assertThat(result.actuator()).isTrue();
    }
}
