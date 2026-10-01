package com.lily.builder;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 레포 파일로 컨테이너 포트, 필요한 DB, Spring actuator 사용 여부를 추정한다.
 *
 * <p>레포 주소만 받는 화면(lily-frontend)에서 배포할 때, 포트가 8080 이 아니거나 DB 가 필요 없는 앱도 배포되게 하려고 둔다.
 * 파일은 rootDir 기준 경로로 읽는다. 없으면 {@code read} 가 null 을 돌려준다.
 */
final class AppDetector {

    private static final Pattern EXPOSE = Pattern.compile("(?im)^\\s*EXPOSE\\s+(\\d{1,5})");
    private static final Pattern PRISMA_PROVIDER = Pattern.compile(
            "(?s)datasource\\s+\\w+\\s*\\{[^}]*provider\\s*=\\s*\"(\\w+)\"");

    /** 앞에 있는 파일부터 본다. 첫 번째로 드라이버가 보이는 파일로 정한다 */
    private static final List<Marker> MARKERS = List.of(
            new Marker("build.gradle", "postgres", "org.postgresql", "r2dbc-postgresql"),
            new Marker("build.gradle", "mysql", "mysql-connector", "mariadb-java-client"),
            new Marker("build.gradle.kts", "postgres", "org.postgresql", "r2dbc-postgresql"),
            new Marker("build.gradle.kts", "mysql", "mysql-connector", "mariadb-java-client"),
            new Marker("pom.xml", "postgres", "org.postgresql", "r2dbc-postgresql"),
            new Marker("pom.xml", "mysql", "mysql-connector", "mariadb-java-client"),
            new Marker("package.json", "postgres", "\"pg\"", "\"postgres\"", "\"pg-promise\""),
            new Marker("package.json", "mysql", "\"mysql2\"", "\"mysql\""),
            new Marker("requirements.txt", "postgres", "psycopg", "asyncpg"),
            new Marker("requirements.txt", "mysql", "pymysql", "mysqlclient", "aiomysql"),
            new Marker("pyproject.toml", "postgres", "psycopg", "asyncpg"),
            new Marker("pyproject.toml", "mysql", "pymysql", "mysqlclient", "aiomysql"),
            new Marker("go.mod", "postgres", "github.com/lib/pq", "github.com/jackc/pgx"),
            new Marker("go.mod", "mysql", "github.com/go-sql-driver/mysql"));

    /** 버전 카탈로그(libs.spring.boot.starter.actuator)도 잡으려고 "actuator" 만 본다 */
    private static final List<String> SPRING_BUILD_FILES = List.of(
            "build.gradle", "build.gradle.kts", "pom.xml", "gradle/libs.versions.toml");

    private AppDetector() {
    }

    /**
     * @param read rootDir 기준 경로 → 파일 내용. 없으면 null
     */
    static Result detect(Function<String, String> read) {
        Cache files = new Cache(read);

        Integer port = null;
        String portSource = null;
        String dockerfile = files.get("Dockerfile");
        if (dockerfile != null) {
            Matcher m = EXPOSE.matcher(dockerfile);
            if (m.find()) {
                int value = Integer.parseInt(m.group(1));
                if (value >= 1 && value <= 65535) {
                    port = value;
                    portSource = "Dockerfile EXPOSE";
                }
            }
        }

        String database = null;
        String databaseSource = null;
        String prisma = files.get("prisma/schema.prisma");
        if (prisma != null) {
            Matcher m = PRISMA_PROVIDER.matcher(prisma);
            if (m.find()) {
                String provider = m.group(1).toLowerCase(Locale.ROOT);
                if (provider.equals("postgresql")) {
                    database = "postgres";
                } else if (provider.equals("mysql")) {
                    database = "mysql";
                }
                databaseSource = database == null ? null : "prisma/schema.prisma provider " + provider;
            }
        }
        for (Marker marker : MARKERS) {
            if (database != null) {
                break;
            }
            String content = files.get(marker.file());
            if (content == null) {
                continue;
            }
            String lower = content.toLowerCase(Locale.ROOT);
            for (String token : marker.tokens()) {
                if (lower.contains(token.toLowerCase(Locale.ROOT))) {
                    database = marker.database();
                    databaseSource = marker.file() + ": " + token;
                    break;
                }
            }
        }

        boolean actuator = false;
        for (String file : SPRING_BUILD_FILES) {
            String content = files.get(file);
            if (content != null && content.contains("actuator")) {
                actuator = true;
                break;
            }
        }
        return new Result(port, portSource, database, databaseSource, actuator);
    }

    /**
     * @param port           Dockerfile EXPOSE. 없으면 null
     * @param database       postgres / mysql. 드라이버가 안 보이면 null
     * @param actuator       Spring actuator 가 있으면 true. 없으면 actuator 헬스 경로로 확인할 수 없다
     */
    record Result(Integer port, String portSource, String database, String databaseSource, boolean actuator) {
    }

    private record Marker(String file, String database, String... tokens) {
    }

    /** 같은 파일을 여러 번 받지 않는다 */
    private static final class Cache {
        private final Function<String, String> read;
        private final Map<String, Optional<String>> files = new HashMap<>();

        Cache(Function<String, String> read) {
            this.read = read;
        }

        String get(String path) {
            return files.computeIfAbsent(path, p -> Optional.ofNullable(read.apply(p))).orElse(null);
        }
    }
}
