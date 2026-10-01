package com.lily.builder;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DockerfileGeneratorTest {

    private static DockerfileGenerator.Generated generate(Map<String, String> files) {
        return DockerfileGenerator.generate(files::get);
    }

    @Test
    void Spring_Maven_은_pom_의_자바_버전으로_빌드하고_jar_를_실행한다() {
        DockerfileGenerator.Generated result = generate(Map.of(
                "pom.xml", "<properties><java.version>17</java.version></properties>"));

        assertThat(result.stack()).isEqualTo("spring maven (java 17)");
        assertThat(result.port()).isEqualTo(8080);
        assertThat(result.dockerfile())
                .contains("FROM maven:3.9-eclipse-temurin-17 AS build", "mvn -B -q -DskipTests package",
                        "FROM eclipse-temurin:17-jre", "EXPOSE 8080");
    }

    @Test
    void 자바_버전은_이미지가_있는_LTS_로_올리고_server_port_를_따른다() {
        DockerfileGenerator.Generated result = generate(Map.of(
                "build.gradle", "java { toolchain { languageVersion = JavaLanguageVersion.of(11) } }",
                "src/main/resources/application.yml", "spring:\n  application:\n    name: a\nserver:\n  address: 0.0.0.0\n  port: ${PORT:8081}\n"));

        assertThat(result.stack()).isEqualTo("spring gradle (java 17)");
        assertThat(result.port()).isEqualTo(8081);
        assertThat(result.dockerfile()).contains("FROM gradle:8.14-jdk17 AS build", "EXPOSE 8081");
    }

    @Test
    void Gradle_래퍼가_있으면_CRLF_를_고쳐_래퍼로_빌드한다() {
        DockerfileGenerator.Generated result = generate(Map.of(
                "build.gradle.kts", "java { sourceCompatibility = JavaVersion.VERSION_21 }",
                "gradlew", "#!/bin/sh"));

        assertThat(result.dockerfile()).contains("FROM eclipse-temurin:21-jdk AS build",
                "sed -i 's/\\r$//' gradlew", "./gradlew bootJar -x test");
    }

    @Test
    void Vite_앱은_정적_파일로_빌드해_SPA_로_서빙한다() {
        DockerfileGenerator.Generated result = generate(Map.of(
                "package.json", """
                        {"scripts":{"build":"vite build"},"dependencies":{"react":"^19"},"devDependencies":{"vite":"^8"}}""",
                "package-lock.json", "{}"));

        assertThat(result.stack()).isEqualTo("node static (dist)");
        assertThat(result.port()).isEqualTo(8080);
        assertThat(result.dockerfile()).contains("RUN npm ci && npm run build",
                "try_files $uri $uri/ /index.html", "COPY --from=build /src/dist /usr/share/nginx/html");
    }

    @Test
    void 공개_환경변수는_빌드_단계에_ARG_로_선언하고_비밀_값은_넣지_않는다() {
        DockerfileGenerator.Generated result = DockerfileGenerator.generate(Map.of(
                "package.json", "{\"scripts\":{\"build\":\"vite build\"},\"devDependencies\":{\"vite\":\"^8\"}}")::get,
                java.util.Set.of("VITE_API_URL", "JWT_SECRET"));

        assertThat(result.dockerfile()).contains("ARG VITE_API_URL\nCOPY . .").doesNotContain("JWT_SECRET");
    }

    @Test
    void Next_앱은_pnpm_으로_빌드해_서버로_띄운다() {
        DockerfileGenerator.Generated result = generate(Map.of(
                "package.json", """
                        {"scripts":{"build":"next build","start":"next start"},"dependencies":{"next":"16"},"engines":{"node":">=24"}}""",
                "pnpm-lock.yaml", ""));

        assertThat(result.stack()).isEqualTo("node server (pnpm start)");
        assertThat(result.port()).isEqualTo(3000);
        assertThat(result.dockerfile()).contains("FROM node:24-slim", "corepack enable && pnpm install && pnpm run build",
                "CMD [\"pnpm\", \"start\"]");
    }

    @Test
    void Express_앱은_빌드_스크립트가_없어도_start_로_띄운다() {
        DockerfileGenerator.Generated result = generate(Map.of(
                "package.json", "{\"scripts\":{\"start\":\"node index.js\"},\"dependencies\":{\"express\":\"^5\"}}"));

        assertThat(result.dockerfile()).contains("RUN npm install\n", "CMD [\"npm\", \"start\"]");
    }

    @Test
    void FastAPI_앱은_uvicorn_으로_띄운다() {
        DockerfileGenerator.Generated result = generate(Map.of(
                "requirements.txt", "fastapi==0.115\npsycopg[binary]",
                "app/main.py", "app = FastAPI()"));

        assertThat(result.stack()).isEqualTo("python fastapi (app.main)");
        assertThat(result.port()).isEqualTo(8000);
        assertThat(result.dockerfile()).contains("uvicorn app.main:app --host 0.0.0.0 --port 8000");
    }

    @Test
    void Go_앱은_go_mod_버전으로_빌드한다() {
        DockerfileGenerator.Generated result = generate(Map.of("go.mod", "module x\n\ngo 1.23.4\n"));

        assertThat(result.dockerfile()).contains("FROM golang:1.23 AS build", "EXPOSE 8080");
    }

    @Test
    void index_html_만_있으면_정적_사이트() {
        assertThat(generate(Map.of("index.html", "<html>")).stack()).isEqualTo("static html");
    }

    @Test
    void 빌드_파일이_없으면_null() {
        // 백엔드·프론트가 하위 폴더에 있는 레포 루트 (README 만 있다)
        assertThat(generate(Map.of("README.md", "# app"))).isNull();
    }
}
