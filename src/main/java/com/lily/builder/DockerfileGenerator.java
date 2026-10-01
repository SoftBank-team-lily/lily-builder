package com.lily.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 레포에 Dockerfile 이 없을 때 빌드 파일을 보고 Dockerfile 을 만든다.
 *
 * <p>화면은 GitHub 주소만 받는다. 사용자가 Dockerfile 을 쓰지 않아도 흔한 구성(Spring Maven/Gradle, Node, Python, Go,
 * 정적 HTML)은 배포되게 하려고 둔다. 파일은 rootDir 기준 경로로 읽는다. 모르는 구성이면 null.
 */
final class DockerfileGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 이미지가 있는 LTS 버전. 요청한 버전 이상인 첫 버전을 쓴다 */
    private static final List<Integer> JAVA_VERSIONS = List.of(17, 21, 25);
    private static final Pattern MAVEN_JAVA = Pattern.compile(
            "<(?:java\\.version|maven\\.compiler\\.release|maven\\.compiler\\.target|release)>\\s*(?:1\\.)?(\\d+)");
    private static final Pattern GRADLE_JAVA = Pattern.compile(
            "(?:JavaLanguageVersion\\.of\\(\\s*|VERSION_(?:1_)?|(?:source|target)Compatibility\\s*=\\s*['\"]?(?:1\\.)?)(\\d+)");
    /** server.port=8081, server.port: ${PORT:8081}, yml 의 server: 아래 port: 8081 */
    private static final Pattern SPRING_PORT = Pattern.compile(
            "(?m)(?:^server\\.port\\s*[=:]\\s*|^server:\\s*\\n(?:[ \\t]+.*\\n)*?[ \\t]+port:\\s*)(?:\\$\\{\\w+:)?(\\d{2,5})");
    private static final Pattern GO_VERSION = Pattern.compile("(?m)^go\\s+(\\d+\\.\\d+)");
    private static final List<String> NODE_SERVERS = List.of(
            "express", "fastify", "koa", "@nestjs/core", "hono", "@hapi/hapi", "next", "nuxt", "@remix-run/serve",
            "@sveltejs/kit", "astro");

    /**
     * 브라우저 코드에 빌드할 때 박히는 공개 환경변수. 실행할 때 넣으면 늦어서 빌드 인자로도 넘긴다.
     * 공개용이라 이미지 기록에 남아도 된다. 비밀 값은 이 이름으로 시작하지 않는다
     */
    static final Pattern PUBLIC_ENV = Pattern.compile("^(VITE_|NEXT_PUBLIC_|REACT_APP_|NUXT_PUBLIC_|PUBLIC_)\\w*$");

    private DockerfileGenerator() {
    }

    /**
     * @param stack 로그에 남길 한 줄. 예: {@code spring maven (java 17)}
     */
    record Generated(String stack, int port, String dockerfile) {

        /** 서버 없이 빌드 결과(정적 파일)만 내보내는 프론트 */
        boolean isStatic() {
            return stack.startsWith("node static") || stack.startsWith("static html");
        }
    }

    private static final Pattern RUN_LINE = Pattern.compile("(?m)^(?:ENTRYPOINT|CMD) (\\[.*])\\s*$");
    private static final Pattern STATIC_OUTPUT = Pattern.compile("COPY --from=build /src/(\\S+) /usr/share/nginx/html");

    /**
     * 백엔드와 프론트가 한 레포의 폴더에 따로 있을 때 한 이미지로 묶는다. 레포 주소 하나로 주소 하나에 둘 다 뜬다.
     *
     * <p>앞에서 Caddy 가 받는다. /api 와, 프론트 파일도 브라우저 페이지 요청도 아닌 요청(fetch, 헬스 체크)은 백엔드로,
     * 나머지는 프론트 빌드 결과(없는 경로는 index.html)를 준다.
     * 프론트 코드에 박힌 {@code http://localhost:{백엔드 포트}} 는 빌드 전에 지워 같은 주소로 부르게 한다.
     * 같은 출처 요청이라 Origin 은 떼고 넘긴다. 프록시 뒤 백엔드는 자기 주소를 http://127.0.0.1 로 보므로 CORS 로 막는다.
     * Caddy 는 백엔드 포트가 열린 뒤에 띄운다. 그래서 TCP 헬스 체크도 백엔드가 뜬 뒤에 통과한다.
     *
     * @param backend  서버 앱 ({@link Generated#isStatic()} 이 아님). 파일은 레포 루트 컨텍스트에서 backendDir 로 복사한다
     * @param frontend 정적 프론트
     */
    static Generated combine(String backendDir, Generated backend, String frontendDir, Generated frontend) {
        int port = backend.port() == 8080 ? 3000 : 8080;
        String rewrite = "RUN grep -rlE --exclude-dir=node_modules 'https?://(localhost|127\\.0\\.0\\.1):" + backend.port()
                + "' . | xargs -r sed -i -E 's#https?://(localhost|127\\.0\\.0\\.1):" + backend.port() + "##g'\n";

        String front;
        if (frontend.stack().startsWith("static html")) {
            front = "FROM debian:bookworm-slim AS frontend-build\nWORKDIR /out\nCOPY " + frontendDir + "/ .\n" + rewrite;
        } else {
            Matcher output = STATIC_OUTPUT.matcher(frontend.dockerfile());
            if (!output.find()) {
                throw new IllegalStateException("프론트 빌드 결과 폴더를 찾지 못했다: " + frontend.stack());
            }
            String stage = frontend.dockerfile().substring(0, frontend.dockerfile().indexOf("\nFROM ") + 1);
            front = stage.replace("AS build", "AS frontend-build")
                    .replace("COPY . .\n", "COPY " + frontendDir + "/ .\n" + rewrite)
                    + "RUN mv /src/" + output.group(1) + " /out\n";
        }

        String back = backend.dockerfile()
                .replace("AS build", "AS backend-build")
                .replace("--from=build", "--from=backend-build")
                .replace("COPY . .", "COPY " + backendDir + "/ .")
                // distroless 에는 셸이 없어 두 프로세스를 띄울 수 없다
                .replace("gcr.io/distroless/static-debian12", "debian:bookworm-slim")
                .replaceAll("(?m)^EXPOSE .*\\n", "");
        Matcher run = RUN_LINE.matcher(back);
        String command = null;
        int start = -1;
        while (run.find()) {
            start = run.start();
            command = run.group(1);
        }
        if (command == null) {
            throw new IllegalStateException("백엔드 실행 명령을 찾지 못했다: " + backend.stack());
        }
        back = back.substring(0, start);

        List<String> caddyfile = List.of(
                "{", "admin off", "auto_https off", "}",
                ":" + port + " {",
                "root * /srv",
                "@api path /api /api/*",
                "handle @api {", "reverse_proxy 127.0.0.1:" + backend.port() + " {", "header_up -Origin", "}", "}",
                // 폴더(/)는 그 안의 index.html 로 본다. 안 그러면 브라우저가 아닌 GET / 이 백엔드로 간다
                "@file file {path} {path}/index.html",
                "handle @file {", "file_server", "}",
                "@page {", "method GET", "header Accept *text/html*", "}",
                "handle @page {", "rewrite * /index.html", "file_server", "}",
                "handle {", "reverse_proxy 127.0.0.1:" + backend.port() + " {", "header_up -Origin", "}", "}",
                "}");
        // lily-cicd 는 SERVER_PORT 를 앱 포트(= 프록시 포트)로 넣는다. 백엔드는 자기 포트에 그대로 둔다
        String script = "trap 'kill $b $c 2>/dev/null; exit 0' TERM; "
                + "SERVER_PORT=" + backend.port() + " PORT=" + backend.port() + " " + shell(command) + " & b=$!; "
                + "until (echo > /dev/tcp/127.0.0.1/" + backend.port() + ") 2>/dev/null; do kill -0 $b 2>/dev/null || exit 1; sleep 1; done; "
                + "caddy run --config /etc/Caddyfile --adapter caddyfile & c=$!; wait -n; exit 1";
        String cmd;
        try {
            cmd = JSON.writeValueAsString(List.of("bash", "-c", script));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        return new Generated(backend.stack() + " + " + frontend.stack() + " (" + backendDir + ", " + frontendDir + ")", port,
                front + "\n" + back
                        + "COPY --from=frontend-build /out /srv\n"
                        + "COPY --from=caddy:2-alpine /usr/bin/caddy /usr/bin/caddy\n"
                        + "RUN printf '%s\\n' " + caddyfile.stream().map(line -> "'" + line + "'").reduce((a, b) -> a + " " + b).orElseThrow()
                        + " > /etc/Caddyfile\n"
                        + "EXPOSE " + port + "\n"
                        + "CMD " + cmd + "\n");
    }

    /** ["java", "-jar", "/app/app.jar"] → 'java' '-jar' '/app/app.jar' */
    private static String shell(String jsonArray) {
        try {
            List<String> args = JSON.readValue(jsonArray, JSON.getTypeFactory().constructCollectionType(List.class, String.class));
            return args.stream().map(arg -> "'" + arg.replace("'", "'\\''") + "'").reduce((a, b) -> a + " " + b).orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException("실행 명령을 읽지 못했다: " + jsonArray, e);
        }
    }

    /**
     * @param read rootDir 기준 경로 → 파일 내용. 없으면 null
     */
    static Generated generate(Function<String, String> read) {
        return generate(read, java.util.Set.of());
    }

    /**
     * @param envKeys 앱 환경변수 이름. 공개 변수({@link #PUBLIC_ENV})는 Node 빌드 단계에 ARG 로 선언한다
     */
    static Generated generate(Function<String, String> read, java.util.Set<String> envKeys) {
        String pom = read.apply("pom.xml");
        if (pom != null) {
            return maven(pom, read);
        }
        for (String file : List.of("build.gradle", "build.gradle.kts")) {
            String gradle = read.apply(file);
            if (gradle != null) {
                return gradle(gradle, read);
            }
        }
        String packageJson = read.apply("package.json");
        if (packageJson != null) {
            return node(packageJson, read, envKeys);
        }
        String requirements = read.apply("requirements.txt");
        String pyproject = read.apply("pyproject.toml");
        if (requirements != null || pyproject != null) {
            return python(requirements, pyproject, read);
        }
        String goMod = read.apply("go.mod");
        if (goMod != null) {
            return go(goMod);
        }
        if (read.apply("index.html") != null) {
            return new Generated("static html", 8080, """
                    FROM nginxinc/nginx-unprivileged:1.27-alpine
                    COPY . /usr/share/nginx/html
                    EXPOSE 8080
                    """);
        }
        return null;
    }

    private static Generated maven(String pom, Function<String, String> read) {
        int java = javaImage(find(MAVEN_JAVA, pom));
        int port = springPort(read);
        return new Generated("spring maven (java " + java + ")", port, """
                FROM maven:3.9-eclipse-temurin-%1$d AS build
                WORKDIR /src
                COPY . .
                RUN mvn -B -q -DskipTests package \\
                 && cp "$(ls target/*.jar | grep -v -- '-plain\\.jar$' | head -1)" /app.jar

                FROM eclipse-temurin:%1$d-jre
                COPY --from=build /app.jar /app/app.jar
                EXPOSE %2$d
                ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
                """.formatted(java, port));
    }

    private static Generated gradle(String gradle, Function<String, String> read) {
        int java = javaImage(find(GRADLE_JAVA, gradle));
        int port = springPort(read);
        // 래퍼가 있으면 레포가 정한 Gradle 버전을 쓴다. Windows 에서 커밋하면 CRLF 라 실행되지 않으므로 고친다
        String build = read.apply("gradlew") != null
                ? """
                FROM eclipse-temurin:%1$d-jdk AS build
                WORKDIR /src
                COPY . .
                RUN sed -i 's/\\r$//' gradlew && chmod +x gradlew \\
                 && ./gradlew bootJar -x test --no-daemon -q \\
                """.formatted(java)
                : """
                FROM gradle:8.14-jdk%1$d AS build
                WORKDIR /src
                COPY . .
                RUN gradle bootJar -x test --no-daemon -q \\
                """.formatted(java);
        return new Generated("spring gradle (java " + java + ")", port, build + """
                 && cp "$(ls build/libs/*.jar | grep -v -- '-plain\\.jar$' | head -1)" /app.jar

                FROM eclipse-temurin:%1$d-jre
                COPY --from=build /app.jar /app/app.jar
                EXPOSE %2$d
                ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
                """.formatted(java, port));
    }

    private static Generated node(String packageJson, Function<String, String> read, java.util.Set<String> envKeys) {
        JsonNode pkg;
        try {
            pkg = JSON.readTree(packageJson);
        } catch (Exception e) {
            return null;
        }
        JsonNode scripts = pkg.path("scripts");
        boolean hasBuild = scripts.hasNonNull("build");
        String node = nodeVersion(pkg.path("engines").path("node").asText(""));
        String[] pm = packageManager(read);
        String install = pm[1];
        String run = pm[0] + " run";
        String args = envKeys.stream().filter(key -> PUBLIC_ENV.matcher(key).matches()).sorted()
                .map(key -> "ARG " + key + "\n").reduce("", String::concat);

        String staticDir = staticOutput(pkg);
        if (hasBuild && staticDir != null) {
            // SPA: 없는 경로는 index.html 로 보낸다 (react-router 등)
            return new Generated("node static (" + staticDir + ")", 8080, """
                    FROM node:%1$s-slim AS build
                    WORKDIR /src
                    %5$sCOPY . .
                    RUN %2$s && %3$s build

                    FROM nginxinc/nginx-unprivileged:1.27-alpine
                    RUN printf 'server {\\n  listen 8080;\\n  root /usr/share/nginx/html;\\n  location / { try_files $uri $uri/ /index.html; }\\n}\\n' > /etc/nginx/conf.d/default.conf
                    COPY --from=build /src/%4$s /usr/share/nginx/html
                    EXPOSE 8080
                    """.formatted(node, install, run, staticDir, args));
        }

        String start;
        if (scripts.hasNonNull("start")) {
            start = pm[0] + " start";
        } else if (pkg.hasNonNull("main")) {
            start = "node " + pkg.get("main").asText();
        } else if (read.apply("server.js") != null) {
            start = "node server.js";
        } else if (read.apply("index.js") != null) {
            start = "node index.js";
        } else {
            return null;
        }
        String[] cmd = start.split(" ");
        return new Generated("node server (" + start + ")", 3000, """
                FROM node:%1$s-slim
                WORKDIR /app
                %5$sCOPY . .
                RUN %2$s%3$s
                ENV NODE_ENV=production PORT=3000 HOSTNAME=0.0.0.0
                EXPOSE 3000
                CMD ["%4$s"]
                """.formatted(node, install, hasBuild ? " && " + run + " build" : "", String.join("\", \"", cmd), args));
    }

    /** 서버 프레임워크 없이 빌드 결과만 내보내는 앱의 출력 폴더. 서버 앱이면 null */
    private static String staticOutput(JsonNode pkg) {
        if (NODE_SERVERS.stream().anyMatch(name -> has(pkg, name))) {
            return null;
        }
        if (has(pkg, "react-scripts")) {
            return "build";
        }
        if (has(pkg, "vite") || has(pkg, "@vue/cli-service") || has(pkg, "parcel")) {
            return "dist";
        }
        return null;
    }

    private static boolean has(JsonNode pkg, String name) {
        return pkg.path("dependencies").has(name) || pkg.path("devDependencies").has(name);
    }

    /** [실행 명령, 설치 명령] */
    private static String[] packageManager(Function<String, String> read) {
        if (read.apply("pnpm-lock.yaml") != null) {
            return new String[]{"pnpm", "corepack enable && pnpm install"};
        }
        if (read.apply("yarn.lock") != null) {
            return new String[]{"yarn", "corepack enable && yarn install"};
        }
        if (read.apply("package-lock.json") != null) {
            return new String[]{"npm", "npm ci"};
        }
        return new String[]{"npm", "npm install"};
    }

    /** engines.node 의 첫 메이저 버전. 없거나 너무 낮으면 22 */
    private static String nodeVersion(String engines) {
        Matcher m = Pattern.compile("(\\d+)").matcher(engines);
        if (m.find()) {
            int major = Integer.parseInt(m.group(1));
            if (major >= 18 && major <= 30) {
                return String.valueOf(major);
            }
        }
        return "22";
    }

    private static Generated python(String requirements, String pyproject, Function<String, String> read) {
        String deps = ((requirements == null ? "" : requirements) + "\n" + (pyproject == null ? "" : pyproject))
                .toLowerCase(Locale.ROOT);
        String install = requirements != null
                ? "pip install --no-cache-dir -r requirements.txt"
                : "pip install --no-cache-dir .";
        String cmd;
        String stack;
        if (read.apply("manage.py") != null) {
            stack = "python django";
            cmd = "python manage.py migrate --noinput || true; exec python manage.py runserver 0.0.0.0:8000";
        } else {
            String module = pythonModule(read);
            if (module == null) {
                return null;
            }
            if (deps.contains("fastapi")) {
                stack = "python fastapi (" + module + ")";
                install += " && pip install --no-cache-dir uvicorn";
                cmd = "exec uvicorn " + module + ":app --host 0.0.0.0 --port 8000";
            } else if (deps.contains("flask")) {
                stack = "python flask (" + module + ")";
                install += " && pip install --no-cache-dir gunicorn";
                cmd = "exec gunicorn -b 0.0.0.0:8000 " + module + ":app";
            } else {
                stack = "python (" + module + ")";
                cmd = "exec python -m " + module;
            }
        }
        return new Generated(stack, 8000, """
                FROM python:3.12-slim
                WORKDIR /app
                COPY . .
                RUN %s
                ENV PYTHONUNBUFFERED=1 PORT=8000
                EXPOSE 8000
                CMD ["sh", "-c", "%s"]
                """.formatted(install, cmd));
    }

    /** 앱 객체가 있을 만한 모듈. 예: main, app.main */
    private static String pythonModule(Function<String, String> read) {
        for (String file : List.of("main.py", "app.py", "app/main.py", "src/main.py", "server.py", "wsgi.py")) {
            if (read.apply(file) != null) {
                return file.replaceAll("\\.py$", "").replace('/', '.');
            }
        }
        return null;
    }

    private static Generated go(String goMod) {
        String version = find(GO_VERSION, goMod);
        String image = version == null ? "1" : version;
        return new Generated("go " + image, 8080, """
                FROM golang:%s AS build
                WORKDIR /src
                COPY . .
                RUN CGO_ENABLED=0 go build -o /app .

                FROM gcr.io/distroless/static-debian12
                COPY --from=build /app /app
                ENV PORT=8080
                EXPOSE 8080
                ENTRYPOINT ["/app"]
                """.formatted(image));
    }

    private static int springPort(Function<String, String> read) {
        for (String file : List.of("src/main/resources/application.properties",
                "src/main/resources/application.yml", "src/main/resources/application.yaml")) {
            String content = read.apply(file);
            String port = content == null ? null : find(SPRING_PORT, content);
            if (port != null) {
                return Integer.parseInt(port);
            }
        }
        return BuildRequest.DEFAULT_TARGET_PORT;
    }

    private static int javaImage(String requested) {
        int version = requested == null ? 21 : Integer.parseInt(requested);
        return JAVA_VERSIONS.stream().filter(v -> v >= version).findFirst().orElse(JAVA_VERSIONS.getLast());
    }

    private static String find(Pattern pattern, String content) {
        Matcher m = pattern.matcher(content);
        return m.find() ? m.group(1) : null;
    }
}
