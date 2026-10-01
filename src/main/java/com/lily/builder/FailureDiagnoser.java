package com.lily.builder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 실패한 배포의 로그로 원인과 고칠 방법을 정한다. 화면은 이걸 보고 입력 칸을 띄우거나 바로 다시 배포한다.
 *
 * <p>lily-cicd 가 Ready 실패 때 남긴 "diagnosis:" 줄(컨테이너 상태, 이벤트, 마지막 로그)과 빌드 로그를 본다.
 * 알려진 실패는 규칙으로 정하고, 모르는 실패만 AI 에 묻는다.
 */
@Component
public class FailureDiagnoser {

    /**
     * @param type    env / port / database / healthPath / rootDir
     * @param env     type 이 env 일 때 환경변수 이름
     * @param kind    GENERATE 면 플랫폼이 만든다, DEFAULT 면 value 를 넣는다, INPUT 이면 사용자에게 묻는다
     * @param value   넣을 값. INPUT 이면 null
     * @param hint    화면에 보일 설명
     * @param options rootDir 처럼 고를 값이 있을 때
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fix(String type, String env, ConfigAdvisor.Kind kind, String value, String hint, List<String> options) {
        /** 사용자에게 묻지 않고 고칠 수 있다 */
        public boolean isAuto() {
            return kind != ConfigAdvisor.Kind.INPUT;
        }
    }

    /**
     * @param cause  사용자에게 보일 원인
     * @param fixes  고칠 방법. 비어 있으면 코드를 고쳐야 한다
     * @param source rule / ai
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Diagnosis(String cause, List<Fix> fixes, String source) {
        public boolean isAutoFixable() {
            return !fixes.isEmpty() && fixes.stream().allMatch(Fix::isAuto);
        }
    }

    /**
     * @param appPort  컨테이너 포트 (요청 또는 감지값)
     * @param database 붙인 DB. 없으면 null
     * @param detected 레포에서 감지한 DB. 없으면 null
     */
    public record Context(Integer appPort, String database, String detected) {
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("Could not resolve placeholder '([^']+)'");
    private static final Pattern KEY_ERROR = Pattern.compile("KeyError: '([A-Z][A-Z0-9_]+)'");
    private static final Pattern DATASOURCE = Pattern.compile(
            "Failed to configure a DataSource|Failed to determine a suitable driver class|url' attribute is not specified");
    private static final Pattern TABLE_MISSING = Pattern.compile(
            "relation \"[^\"]+\" does not exist|Table '[^']+' doesn't exist|Schema-validation: missing table");
    private static final Pattern OOM = Pattern.compile("OOMKilled|OutOfMemoryError|JavaScript heap out of memory|lastExit=137");
    private static final Pattern LISTEN = Pattern.compile(
            "(?:Tomcat|Netty|Jetty|Undertow) started on port\\(?s?\\)?:? (\\d{2,5})|[Ll]istening (?:on|at) (?:port )?(?:https?://)?[^\\s:]*:?(\\d{2,5})\\b"
                    + "|Local:\\s+https?://[^:\\s]+:(\\d{2,5})|Uvicorn running on https?://[^:\\s]+:(\\d{2,5})"
                    + "|started server on [^,]*?:(\\d{2,5})|Running on https?://[^:\\s]+:(\\d{2,5})");
    private static final Pattern PROBE_STATUS = Pattern.compile("probe failed: HTTP probe failed with statuscode: (\\d{3})");
    private static final Pattern MULTI_APP = Pattern.compile("앱 폴더가 여러 개라 하나로 정하지 못했다 \\(([^)]*)\\)");
    private static final Pattern NOTHING = Pattern.compile("Dockerfile 이 없고 빌드 방법도 찾지 못했다");

    private final ConfigAdvisor config;
    private final AiAdvisor ai;

    public FailureDiagnoser(ConfigAdvisor config, AiAdvisor ai) {
        this.config = config;
        this.ai = ai;
    }

    /**
     * @param logs    빌드 로그 전체
     * @param context 포트, DB
     * @param scanned 레포의 설정 키 판단. 실패 로그의 키를 같은 기준으로 채운다. 모르면 빈 목록
     */
    public Diagnosis diagnose(List<String> logs, Context context, List<ConfigAdvisor.Advice> scanned) {
        String text = String.join("\n", logs);
        Map<String, ConfigAdvisor.Advice> known = new LinkedHashMap<>();
        scanned.forEach(advice -> known.put(advice.env(), advice));
        Function<String, ConfigAdvisor.Advice> lookup = env -> known.get(env);

        Diagnosis rule = rules(text, context, scanned, lookup);
        if (rule != null) {
            return rule;
        }
        if (ai.enabled()) {
            Diagnosis ai = ai(logs, context);
            if (ai != null) {
                return ai;
            }
        }
        return new Diagnosis(lastError(logs), List.of(), "rule");
    }

    private Diagnosis rules(String text, Context context, List<ConfigAdvisor.Advice> scanned,
                            Function<String, ConfigAdvisor.Advice> lookup) {
        Matcher multi = MULTI_APP.matcher(text);
        if (multi.find()) {
            List<String> dirs = new ArrayList<>();
            for (String part : multi.group(1).split(",\\s*")) {
                dirs.add(part.strip().split("\\s+")[0]);
            }
            return new Diagnosis("레포에 앱 폴더가 여러 개라 어느 것을 배포할지 정하지 못했어요.",
                    List.of(new Fix("rootDir", null, ConfigAdvisor.Kind.INPUT, dirs.isEmpty() ? null : dirs.get(0),
                            "배포할 앱 폴더를 골라 주세요.", dirs)), "rule");
        }
        if (NOTHING.matcher(text).find()) {
            return new Diagnosis("레포에서 빌드 방법을 찾지 못했어요. Dockerfile 이나 빌드 파일(pom.xml, package.json 등)이 있는 폴더를 지정해 주세요.",
                    List.of(), "rule");
        }

        // 설정 누락: 로그에 나온 키 + 같은 레포에서 미리 찾아 둔 필수 키를 한 번에 채운다
        Set<String> missing = new LinkedHashSet<>();
        Matcher placeholder = PLACEHOLDER.matcher(text);
        Map<String, String> properties = new LinkedHashMap<>();
        while (placeholder.find()) {
            String property = placeholder.group(1);
            String env = property.matches("[A-Z][A-Z0-9_]*") ? property : ConfigScanner.envName(property);
            missing.add(env);
            properties.put(env, property.matches("[A-Z][A-Z0-9_]*") ? null : property);
        }
        Matcher keyError = KEY_ERROR.matcher(text);
        while (keyError.find()) {
            missing.add(keyError.group(1));
        }
        if (!missing.isEmpty()) {
            List<Fix> fixes = new ArrayList<>();
            Set<String> added = new LinkedHashSet<>();
            for (String env : missing) {
                ConfigAdvisor.Advice advice = lookup.apply(env);
                if (advice == null) {
                    advice = config.advise(properties.get(env), env);
                }
                fixes.add(envFix(advice));
                added.add(env);
            }
            for (ConfigAdvisor.Advice advice : scanned) {
                if (advice.required() && added.add(advice.env())) {
                    fixes.add(envFix(advice));
                }
            }
            return new Diagnosis("앱이 기동할 때 필요한 설정 " + String.join(", ", missing) + " 이(가) 없어서 시작하지 못했어요.",
                    fixes, "rule");
        }

        if (DATASOURCE.matcher(text).find() && context.database() == null) {
            String engine = context.detected() == null ? "postgres" : context.detected();
            return new Diagnosis("앱이 DB 접속 정보를 찾지 못했어요. DB 를 붙여서 다시 배포해요.",
                    List.of(new Fix("database", null, ConfigAdvisor.Kind.DEFAULT, engine,
                            "플랫폼이 " + engine + " DB 를 만들어 접속 정보를 넣어요.", List.of())), "rule");
        }
        if (TABLE_MISSING.matcher(text).find()) {
            return new Diagnosis("DB 에 앱이 쓰는 테이블이 없어요. JPA 가 테이블을 만들게 해서 다시 배포해요.",
                    List.of(new Fix("env", "SPRING_JPA_HIBERNATE_DDL_AUTO", ConfigAdvisor.Kind.DEFAULT, "update",
                            "마이그레이션 도구가 없을 때 JPA 가 테이블을 만들어요.", List.of())), "rule");
        }
        if (OOM.matcher(text).find()) {
            return new Diagnosis("앱이 메모리를 다 써서 종료됐어요 (OOM). 앱의 메모리 사용을 줄이거나 힙 크기를 낮춰야 해요.",
                    List.of(), "rule");
        }
        Matcher listen = LISTEN.matcher(text);
        Integer listening = null;
        while (listen.find()) {
            for (int g = 1; g <= listen.groupCount(); g++) {
                if (listen.group(g) != null) {
                    listening = Integer.parseInt(listen.group(g));
                }
            }
        }
        if (listening != null && context.appPort() != null && !listening.equals(context.appPort())
                && listening > 0 && listening < 65536) {
            return new Diagnosis("앱은 " + listening + " 포트에서 뜨는데 플랫폼은 " + context.appPort()
                    + " 포트로 확인해서 준비되지 않은 것으로 봤어요.",
                    List.of(new Fix("port", null, ConfigAdvisor.Kind.DEFAULT, listening.toString(),
                            "컨테이너 포트를 " + listening + " 로 바꿔요.", List.of())), "rule");
        }
        Matcher probe = PROBE_STATUS.matcher(text);
        if (probe.find()) {
            return new Diagnosis("앱은 떴지만 헬스 체크 주소가 " + probe.group(1) + " 을 돌려줘서 준비되지 않은 것으로 봤어요.",
                    List.of(new Fix("healthPath", null, ConfigAdvisor.Kind.DEFAULT, BuildService.TCP_HEALTH,
                            "주소 대신 포트가 열렸는지만 확인해요.", List.of())), "rule");
        }
        return null;
    }

    private Diagnosis ai(List<String> logs, Context context) {
        List<String> tail = logs.subList(Math.max(0, logs.size() - 80), logs.size());
        String prompt = "앱 포트: " + context.appPort() + "\n붙인 DB: " + context.database()
                + "\n레포에서 감지한 DB: " + context.detected() + "\n\n로그:\n" + String.join("\n", tail);
        return ai.diagnose(prompt).map(answer -> {
            List<Fix> fixes = new ArrayList<>();
            for (AiAdvisor.FailureFix fix : answer.fixes() == null ? List.<AiAdvisor.FailureFix>of() : answer.fixes()) {
                String type = fix.type() == null ? "" : fix.type().strip();
                switch (type) {
                    case "env" -> {
                        if (fix.env() != null && fix.env().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                            ConfigAdvisor.Advice advice = config.advise(null, fix.env());
                            String value = blankToNull(fix.value());
                            ConfigAdvisor.Kind kind = advice.kind() == ConfigAdvisor.Kind.GENERATE ? advice.kind()
                                    : value != null && advice.kind() != ConfigAdvisor.Kind.INPUT
                                    ? ConfigAdvisor.Kind.DEFAULT : advice.kind();
                            fixes.add(new Fix("env", fix.env(), kind,
                                    kind == ConfigAdvisor.Kind.DEFAULT ? (value != null ? value : advice.value()) : null,
                                    fix.hint(), List.of()));
                        }
                    }
                    case "port" -> {
                        if (fix.value() != null && fix.value().matches("\\d{2,5}")) {
                            fixes.add(new Fix("port", null, ConfigAdvisor.Kind.DEFAULT, fix.value(), fix.hint(), List.of()));
                        }
                    }
                    case "database" -> {
                        String engine = fix.value() != null && fix.value().matches("postgres|mysql") ? fix.value() : "postgres";
                        fixes.add(new Fix("database", null, ConfigAdvisor.Kind.DEFAULT, engine, fix.hint(), List.of()));
                    }
                    case "healthPath" -> fixes.add(new Fix("healthPath", null, ConfigAdvisor.Kind.DEFAULT,
                            fix.value() == null || fix.value().isBlank() ? BuildService.TCP_HEALTH : fix.value(),
                            fix.hint(), List.of()));
                    default -> {
                    }
                }
            }
            return new Diagnosis(answer.cause(), fixes, "ai");
        }).orElse(null);
    }

    private static Fix envFix(ConfigAdvisor.Advice advice) {
        return new Fix("env", advice.env(), advice.kind(), advice.value(), advice.hint(), List.of());
    }

    /** 규칙도 AI 도 모를 때: 실패 이유 줄 */
    static String lastError(List<String> logs) {
        for (int i = logs.size() - 1; i >= 0; i--) {
            String line = logs.get(i);
            if (line.startsWith("failed: ") || line.startsWith("agent: FAILED")) {
                return line.replaceFirst("^agent: FAILED\\s*", "").replaceFirst("^failed:\\s*", "");
            }
        }
        return logs.isEmpty() ? "배포하지 못했어요." : logs.getLast();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
