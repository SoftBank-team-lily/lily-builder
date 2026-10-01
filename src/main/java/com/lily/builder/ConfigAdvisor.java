package com.lily.builder;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@link ConfigScanner} 가 찾은 키마다 값을 어떻게 채울지 정한다.
 *
 * <ul>
 *   <li>GENERATE: 앱 안에서만 쓰는 비밀값. 플랫폼(lily-frontend)이 랜덤으로 만든다</li>
 *   <li>DEFAULT: 정해진 값이 있다 (예시 파일 값, 만료 시간, ddl-auto)</li>
 *   <li>INPUT: 외부 서비스 키처럼 사용자만 아는 값</li>
 * </ul>
 * 규칙으로 먼저 정하고, AI(Claude 또는 OpenAI)가 켜져 있으면 설명과 애매한 키의 판단을 받는다.
 * 외부 서비스 이름이 들어간 키는 AI 가 뭐라 해도 INPUT 이다 (값을 지어내지 않는다).
 */
@Component
public class ConfigAdvisor {

    public enum Kind { GENERATE, DEFAULT, INPUT }

    /**
     * @param env      컨테이너 환경변수 이름
     * @param property Spring 키. 없으면 null
     * @param kind     채우는 방법
     * @param value    DEFAULT 일 때 넣을 값. 그 외 null
     * @param hint     화면에 보일 설명
     * @param required 없으면 앱이 뜨지 않는다
     * @param source   찾은 파일
     */
    public record Advice(String env, String property, Kind kind, String value, String hint, boolean required,
                         String source) {
    }

    private static final Pattern VENDOR = Pattern.compile(
            "(?i)(CLIENT|API|OAUTH|AWS|S3|STRIPE|TOSS|IAMPORT|NAVER|KAKAO|GOOGLE|OPENAI|ANTHROPIC|CLAUDE|GEMINI|GITHUB"
                    + "|SLACK|DISCORD|TWILIO|FIREBASE|SMTP|MAIL|SENDGRID|ALADIN|TMDB|SENTRY|CLOUDINARY|SUPABASE"
                    + "|ACCESS_KEY|WEBHOOK|DSN|REDIS|MONGO)");
    private static final Pattern INTERNAL_SECRET = Pattern.compile(
            "(?i)(JWT|SESSION|COOKIE|SIGNING|ENCRYPT|CRYPTO|HMAC|CSRF|TOKEN_SECRET|APP_SECRET|SECRET_KEY_BASE"
                    + "|NEXTAUTH_SECRET|AUTH_SECRET|^SECRET_KEY$|^SECRET$)");
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)^$|^(your|my|insert|enter|put)[-_ ]|^<.*>$|^\\[.*]$|x{3,}|change[-_ ]?me|replace|todo|example|dummy"
                    + "|_here$|-here$|^\\$\\{|^\\*+$|^sk-\\.\\.\\.");
    private static final Pattern DURATION = Pattern.compile("(?i)(EXPIRATION|EXPIRE|EXPIRES_IN|EXPIRY|TTL|VALIDITY)(_MS|_MILLIS|_SECONDS|_SEC)?$");

    private final AiAdvisor ai;

    public ConfigAdvisor(AiAdvisor ai) {
        this.ai = ai;
    }

    public List<Advice> advise(ConfigScanner.Result scanned) {
        Map<String, Advice> advices = new LinkedHashMap<>();
        for (ConfigScanner.Key key : scanned.keys()) {
            advices.put(key.env(), rule(key));
        }
        if (advices.isEmpty() || !ai.enabled()) {
            return List.copyOf(advices.values());
        }
        StringBuilder facts = new StringBuilder();
        for (ConfigScanner.Key key : scanned.keys()) {
            facts.append("- env=").append(key.env())
                    .append(key.property() == null ? "" : " spring=" + key.property())
                    .append(" required=").append(key.required())
                    .append(" file=").append(key.source())
                    .append(" code=").append(key.line())
                    .append(key.example() == null ? "" : " example=" + key.example())
                    .append('\n');
        }
        ai.classify(facts.toString()).ifPresent(answer -> {
            for (AiAdvisor.ConfigKey ai : answer.keys() == null ? List.<AiAdvisor.ConfigKey>of() : answer.keys()) {
                Advice base = advices.get(ai.env());
                if (base != null) {
                    advices.put(ai.env(), merge(base, ai));
                }
            }
        });
        return List.copyOf(advices.values());
    }

    /** 실패 로그에서 찾은 키처럼 스캔 결과가 없을 때 */
    public Advice advise(String property, String env) {
        return rule(new ConfigScanner.Key(property, env, "log", null, "", true));
    }

    static Advice rule(ConfigScanner.Key key) {
        String env = key.env();
        String example = key.example();
        boolean vendor = VENDOR.matcher(env).find();
        if (!vendor && INTERNAL_SECRET.matcher(env).find() && looksSecret(env)) {
            return new Advice(env, key.property(), Kind.GENERATE, null,
                    "앱 안에서만 쓰는 비밀값이라 플랫폼이 랜덤으로 만들어요.", key.required(), key.source());
        }
        if (example != null && !PLACEHOLDER.matcher(example.strip()).find() && !(vendor && looksSecret(env))) {
            return new Advice(env, key.property(), Kind.DEFAULT, example, "예시 설정 파일의 값이에요.", key.required(), key.source());
        }
        if (env.equals("SPRING_JPA_HIBERNATE_DDL_AUTO")) {
            return new Advice(env, key.property(), Kind.DEFAULT, "update",
                    "마이그레이션 도구가 없어 JPA 가 테이블을 만들게 해요.", key.required(), key.source());
        }
        if (DURATION.matcher(env).find() && !vendor) {
            boolean seconds = env.matches("(?i).*(_SECONDS|_SEC)$");
            return new Advice(env, key.property(), Kind.DEFAULT, seconds ? "3600" : "3600000",
                    seconds ? "만료 시간(초). 1시간이에요." : "만료 시간(밀리초). 1시간이에요.", key.required(), key.source());
        }
        if (env.matches("(?i).*OPENAI.*MODEL$")) {
            return new Advice(env, key.property(), Kind.DEFAULT, "gpt-4o-mini", "OpenAI 모델 이름이에요.", key.required(), key.source());
        }
        return new Advice(env, key.property(), Kind.INPUT, null, inputHint(env), key.required(), key.source());
    }

    private static Advice merge(Advice base, AiAdvisor.ConfigKey ai) {
        Kind kind = parse(ai.kind());
        String hint = ai.hint() == null || ai.hint().isBlank() ? base.hint() : ai.hint();
        if (kind == null) {
            return new Advice(base.env(), base.property(), base.kind(), base.value(), hint, base.required(), base.source());
        }
        boolean vendor = VENDOR.matcher(base.env()).find() && looksSecret(base.env());
        if (vendor && kind != Kind.INPUT) {
            // 외부 서비스 키는 지어낼 수 없다
            return new Advice(base.env(), base.property(), Kind.INPUT, null, hint, base.required(), base.source());
        }
        if (kind == Kind.INPUT && base.kind() == Kind.DEFAULT && base.value() != null) {
            // 규칙이 정한 기본값(예시 파일 값, 모델 이름)은 AI 가 입력으로 봐도 미리 채워 둔다. 화면에서 고칠 수 있다
            return new Advice(base.env(), base.property(), Kind.DEFAULT, base.value(), hint, base.required(), base.source());
        }
        String value = kind == Kind.DEFAULT ? blankToNull(ai.value()) : null;
        if (kind == Kind.DEFAULT && value == null) {
            value = base.value();
            kind = value == null ? Kind.INPUT : kind;
        }
        return new Advice(base.env(), base.property(), kind, value, hint, base.required(), base.source());
    }

    private static boolean looksSecret(String env) {
        return env.matches("(?i).*(SECRET|KEY|TOKEN|PASSWORD|PASSWD|CREDENTIAL|SALT|PEPPER).*");
    }

    private static String inputHint(String env) {
        String upper = env.toUpperCase(Locale.ROOT);
        if (upper.contains("KAKAO")) return "카카오 개발자 콘솔(developers.kakao.com) 앱 키예요.";
        if (upper.contains("NAVER")) return "네이버 개발자센터(developers.naver.com) 애플리케이션 값이에요.";
        if (upper.contains("OPENAI")) return "OpenAI API 키예요 (platform.openai.com).";
        if (upper.contains("ANTHROPIC") || upper.contains("CLAUDE")) return "Anthropic API 키예요 (console.anthropic.com).";
        if (upper.contains("GOOGLE")) return "Google Cloud 콘솔에서 발급한 값이에요.";
        if (upper.contains("GITHUB")) return "GitHub 에서 발급한 값이에요.";
        if (upper.contains("ALADIN")) return "알라딘 TTB 키예요 (aladin.co.kr/ttb).";
        if (upper.matches(".*(SMTP|MAIL).*")) return "메일 서버 값이에요.";
        return "앱이 기동할 때 읽는 값이에요.";
    }

    private static Kind parse(String kind) {
        if (kind == null) return null;
        return switch (kind.strip().toLowerCase(Locale.ROOT)) {
            case "generate" -> Kind.GENERATE;
            case "default" -> Kind.DEFAULT;
            case "input" -> Kind.INPUT;
            default -> null;
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 테스트와 진단이 함께 쓴다 */
    static List<Advice> rules(List<ConfigScanner.Key> keys) {
        List<Advice> result = new ArrayList<>();
        keys.forEach(key -> result.add(rule(key)));
        return result;
    }
}
