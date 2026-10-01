package com.lily.builder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 앱이 기동하려면 꼭 있어야 하는 설정 키를 소스에서 찾는다. 빌드는 하지 않는다.
 *
 * <p>application.yml 을 .gitignore 로 빼 두고 예시 파일만 올린 레포는 이미지에 설정이 없어서
 * {@code @Value("${jwt.secret}")} 같은 기본값 없는 키에서 기동하자마자 죽는다.
 * 그런 키를 배포 전에 찾아 환경변수 이름으로 바꿔 둔다. 값은 {@link ConfigAdvisor} 가 정한다.
 *
 * <ul>
 *   <li>Spring: 기본값 없는 {@code @Value} 키 중 커밋된 application.yml/properties 에 값이 없는 것,
 *       커밋된 설정 파일 안의 기본값 없는 {@code ${ENV}}</li>
 *   <li>Node: 대신할 값({@code ||}, {@code ??}) 없이 읽는 {@code process.env.X}</li>
 *   <li>Python: {@code os.environ["X"]}</li>
 * </ul>
 * 플랫폼이 넣는 값(DB 접속, 포트)은 뺀다.
 */
final class ConfigScanner {

    /** 소스를 너무 많이 받지 않는다. 큰 레포는 앞에서부터 이만큼만 본다 */
    static final int MAX_SOURCES = 400;

    private static final Pattern VALUE_ANNOTATION = Pattern.compile("@Value\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\\\?\\$\\{([^:}\\s]+)(:[^}]*)?}");
    private static final Pattern NODE_ENV = Pattern.compile(
            "process\\.env(?:\\.([A-Z][A-Z0-9_]*)|\\[\\s*['\"]([A-Z][A-Z0-9_]*)['\"]\\s*])");
    private static final Pattern NODE_FALLBACK = Pattern.compile("^\\s*(\\|\\||\\?\\?|\\?\\s|!==|===|!=|==)");
    private static final Pattern PYTHON_ENV = Pattern.compile("os\\.environ\\[\\s*['\"]([A-Z][A-Z0-9_]*)['\"]\\s*]");
    private static final Pattern EXAMPLE_FILE = Pattern.compile(
            "(?i)(^|/)(application[-.](example|sample|template)\\.(ya?ml|properties)"
                    + "|application\\.(ya?ml|properties)\\.(example|sample|template)"
                    + "|\\.env\\.(example|sample|template)|env\\.example)$");
    private static final List<String> COMMITTED_CONFIG = List.of(
            "src/main/resources/application.yml", "src/main/resources/application.yaml",
            "src/main/resources/application.properties");

    /** 플랫폼이 넣는다 (lily-cicd DB Secret, 포트, 슬롯 색) */
    private static final Set<String> PLATFORM_ENV = Set.of(
            "PORT", "HOST", "HOSTNAME", "NODE_ENV", "DATABASE_URL", "DB_URL", "DB_USERNAME", "DB_USER", "DB_PASSWORD",
            "DB_HOST", "DB_PORT", "DB_NAME", "DB_POOL_SIZE", "APP_COLOR", "APP_VERSION", "SERVER_PORT");
    private static final List<String> PLATFORM_PROPERTY_PREFIXES = List.of(
            "spring.datasource.", "server.port", "spring.application.name", "spring.profiles.");

    private ConfigScanner() {
    }

    /**
     * @param property Spring 키. 환경변수로만 읽는 키면 null
     * @param env      컨테이너에 넣을 환경변수 이름
     * @param source   찾은 파일 (앱 폴더 기준)
     * @param example  예시 파일에 적힌 값. 없으면 null
     * @param line     찾은 줄 (AI 가 키의 쓰임을 보도록)
     * @param required 없으면 기동하지 못한다 (Spring {@code @Value}, Python {@code os.environ[...]}).
     *                 Node 의 {@code process.env.X} 는 없어도 뜨는 경우가 많아 false
     */
    record Key(String property, String env, String source, String example, String line, boolean required) {
    }

    /**
     * @param keys       채워야 하는 키
     * @param jpa        Spring Data JPA 를 쓴다
     * @param migrations Flyway·Liquibase 나 마이그레이션 폴더가 있다 (테이블을 앱이 만들지 않는다)
     */
    record Result(List<Key> keys, boolean jpa, boolean migrations) {
        static final Result EMPTY = new Result(List.of(), false, false);
    }

    /** {@link #scan} 이 읽는 파일. 미리 한꺼번에 받아 둘 때 쓴다 */
    static List<String> wanted(List<String> paths) {
        List<String> files = new ArrayList<>();
        for (String path : paths) {
            if (COMMITTED_CONFIG.contains(path) || path.equals("pom.xml") || path.equals("build.gradle")
                    || path.equals("build.gradle.kts")
                    || (EXAMPLE_FILE.matcher(path).find() && !path.contains("node_modules/"))) {
                files.add(path);
            }
        }
        int sources = 0;
        for (String path : paths) {
            if (sources < MAX_SOURCES && Language.of(path) != null) {
                files.add(path);
                sources++;
            }
        }
        return files;
    }

    /**
     * @param paths 앱 폴더 기준 파일 경로 전체
     * @param read  앱 폴더 기준 경로 → 내용. 없으면 null
     */
    static Result scan(List<String> paths, Function<String, String> read) {
        Map<String, String> committed = new LinkedHashMap<>();
        String committedText = "";
        for (String file : COMMITTED_CONFIG) {
            if (paths.contains(file)) {
                String text = read.apply(file);
                if (text != null) {
                    committed.putAll(flatten(file, text));
                    committedText += "\n" + text;
                }
            }
        }
        Map<String, String> examples = new LinkedHashMap<>();
        for (String path : paths) {
            if (EXAMPLE_FILE.matcher(path).find() && !path.contains("node_modules/")) {
                String text = read.apply(path);
                if (text != null) {
                    flatten(path, text).forEach(examples::putIfAbsent);
                }
            }
        }

        String build = firstNonNull(read, paths, "pom.xml", "build.gradle", "build.gradle.kts");
        boolean jpa = build != null && build.contains("data-jpa");
        boolean migrations = (build != null && (build.contains("flyway") || build.contains("liquibase")))
                || paths.stream().anyMatch(p -> p.startsWith("src/main/resources/db/migration/"));

        Map<String, Key> keys = new LinkedHashMap<>();
        int sources = 0;
        for (String path : paths) {
            if (sources >= MAX_SOURCES) {
                break;
            }
            Language language = Language.of(path);
            if (language == null) {
                continue;
            }
            String text = read.apply(path);
            sources++;
            if (text == null) {
                continue;
            }
            switch (language) {
                case JVM -> springKeys(path, text, committed, examples, keys);
                case NODE -> nodeKeys(path, text, examples, keys);
                case PYTHON -> pythonKeys(path, text, examples, keys);
            }
        }
        // 커밋된 설정 파일이 환경변수를 기본값 없이 읽는다
        Matcher m = PLACEHOLDER.matcher(committedText);
        while (m.find()) {
            String name = m.group(1);
            if (m.group(2) == null && name.matches("[A-Z][A-Z0-9_]*")) {
                add(keys, new Key(null, name, "application config", example(examples, null, name), m.group(), true));
            }
        }

        if (jpa && !migrations && !committed.containsKey("spring.jpa.hibernate.ddl-auto")) {
            // 테이블을 만들 방법이 없다. 예시 설정 대부분이 update 로 둔다
            add(keys, new Key("spring.jpa.hibernate.ddl-auto", "SPRING_JPA_HIBERNATE_DDL_AUTO", "build file",
                    examples.getOrDefault("spring.jpa.hibernate.ddl-auto", "update"), "spring-boot-starter-data-jpa", true));
        }
        return new Result(List.copyOf(keys.values()), jpa, migrations);
    }

    private static void springKeys(String path, String text, Map<String, String> committed,
                                   Map<String, String> examples, Map<String, Key> keys) {
        Matcher annotation = VALUE_ANNOTATION.matcher(text);
        while (annotation.find()) {
            Matcher placeholder = PLACEHOLDER.matcher(annotation.group(1));
            while (placeholder.find()) {
                String property = placeholder.group(1);
                if (placeholder.group(2) != null || platformProperty(property)) {
                    continue;
                }
                String value = committed.get(property);
                if (value != null && !PLACEHOLDER.matcher(value).find()) {
                    continue;
                }
                String env = envName(property);
                add(keys, new Key(property, env, path, example(examples, property, env), lineOf(text, annotation.start()), true));
            }
        }
    }

    private static void nodeKeys(String path, String text, Map<String, String> examples, Map<String, Key> keys) {
        Matcher m = NODE_ENV.matcher(text);
        while (m.find()) {
            String env = m.group(1) != null ? m.group(1) : m.group(2);
            String after = text.substring(m.end(), Math.min(text.length(), m.end() + 6));
            if (NODE_FALLBACK.matcher(after).find()) {
                continue;
            }
            add(keys, new Key(null, env, path, example(examples, null, env), lineOf(text, m.start()), false));
        }
    }

    private static void pythonKeys(String path, String text, Map<String, String> examples, Map<String, Key> keys) {
        Matcher m = PYTHON_ENV.matcher(text);
        while (m.find()) {
            add(keys, new Key(null, m.group(1), path, example(examples, null, m.group(1)), lineOf(text, m.start()), true));
        }
    }

    private static void add(Map<String, Key> keys, Key key) {
        if (PLATFORM_ENV.contains(key.env()) || key.env().startsWith("SPRING_DATASOURCE_")) {
            return;
        }
        keys.putIfAbsent(key.env(), key);
    }

    /**
     * Spring 이 환경변수에서 키를 찾는 이름. {@code kakao.rest-api-key} → {@code KAKAO_REST_API_KEY}.
     * 점과 대시를 밑줄로 바꾼 이름도 Spring 이 읽는다 (relaxed binding 의 legacy 형태).
     */
    static String envName(String property) {
        return property.replaceAll("[\\[\\]]", "_").replaceAll("[.\\-]", "_").replaceAll("_+", "_")
                .replaceAll("_$", "").toUpperCase(Locale.ROOT);
    }

    private static boolean platformProperty(String property) {
        return PLATFORM_PROPERTY_PREFIXES.stream().anyMatch(property::startsWith);
    }

    private static String example(Map<String, String> examples, String property, String env) {
        if (property != null && examples.containsKey(property)) {
            return examples.get(property);
        }
        return examples.get(env);
    }

    private static String lineOf(String text, int index) {
        int start = text.lastIndexOf('\n', index) + 1;
        int end = text.indexOf('\n', index);
        String line = text.substring(start, end < 0 ? text.length() : end).strip();
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    private static String firstNonNull(Function<String, String> read, List<String> paths, String... files) {
        for (String file : files) {
            if (paths.contains(file)) {
                String text = read.apply(file);
                if (text != null) {
                    return text;
                }
            }
        }
        return null;
    }

    /** yml / properties / .env 를 점으로 이은 키 → 값으로 편다. 목록과 여러 줄 값은 건너뛴다 */
    static Map<String, String> flatten(String path, String text) {
        Map<String, String> result = new LinkedHashMap<>();
        if (path.endsWith(".yml") || path.endsWith(".yaml") || path.matches(".*\\.ya?ml\\.\\w+$")) {
            Deque<Map.Entry<Integer, String>> parents = new ArrayDeque<>();
            for (String raw : text.split("\\R")) {
                String line = raw.replaceAll("\\s+#.*$", "");
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-") || trimmed.equals("---")) {
                    continue;
                }
                int colon = trimmed.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                int indent = line.length() - line.stripLeading().length();
                while (!parents.isEmpty() && parents.peek().getKey() >= indent) {
                    parents.pop();
                }
                String key = trimmed.substring(0, colon).strip().replaceAll("^['\"]|['\"]$", "");
                String value = trimmed.substring(colon + 1).strip();
                StringBuilder full = new StringBuilder();
                parents.descendingIterator().forEachRemaining(e -> full.append(e.getValue()).append('.'));
                full.append(key);
                if (value.isEmpty() || value.equals("|") || value.equals(">")) {
                    parents.push(Map.entry(indent, key));
                } else {
                    result.put(full.toString(), value.replaceAll("^['\"]|['\"]$", ""));
                }
            }
            return result;
        }
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            line = line.replaceFirst("^export\\s+", "");
            int eq = line.indexOf('=');
            if (eq > 0) {
                result.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip().replaceAll("^['\"]|['\"]$", ""));
            }
        }
        return result;
    }

    private enum Language {
        JVM, NODE, PYTHON;

        static Language of(String path) {
            if (path.contains("/test/") || path.startsWith("test/") || path.contains("/tests/")
                    || path.contains("node_modules/") || path.contains("/dist/") || path.contains("/build/")
                    || path.contains(".next/") || path.contains("/venv/") || path.contains("/.venv/")) {
                return null;
            }
            if ((path.endsWith(".java") || path.endsWith(".kt")) && path.contains("src/main/")) {
                return JVM;
            }
            if (path.matches(".*\\.(js|mjs|cjs|ts|jsx|tsx)$") && !path.matches(".*\\.(test|spec|config)\\.[jt]sx?$")
                    && !path.endsWith(".d.ts")) {
                return NODE;
            }
            if (path.endsWith(".py")) {
                return PYTHON;
            }
            return null;
        }
    }
}
