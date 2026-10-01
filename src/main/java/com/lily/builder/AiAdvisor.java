package com.lily.builder;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 규칙으로 정하기 어려운 판단을 LLM 에 묻는다.
 * {@code ANTHROPIC_API_KEY} 가 있으면 Claude, 없고 {@code OPENAI_API_KEY} 가 있으면 OpenAI, 둘 다 없으면 꺼진다.
 *
 * <p>배포를 막지 않는다: 키가 없거나, 호출이 실패하거나, 늦으면 빈 값을 돌려주고 호출한 쪽이 규칙으로 정한다.
 * 사용자가 넣은 값, 플랫폼이 주입한 DB 비밀번호는 보내지 않는다. 보내는 것은 키 이름, 코드 한 줄, 예시 파일 값,
 * 값을 가린 로그다.
 */
@Component
public class AiAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AiAdvisor.class);
    static final String CLAUDE_MODEL = "claude-opus-5-5";
    static final String OPENAI_DEFAULT_MODEL = "gpt-4o-mini";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AnthropicClient claude;
    private final RestClient openai;
    private final String openaiModel;

    public AiAdvisor() {
        this(System.getenv("ANTHROPIC_API_KEY"), System.getenv("OPENAI_API_KEY"), System.getenv("OPENAI_MODEL"),
                "https://api.openai.com");
    }

    AiAdvisor(String anthropicKey, String openaiKey, String openaiModel, String openaiBase) {
        this.claude = blank(anthropicKey) ? null : AnthropicOkHttpClient.builder()
                .apiKey(anthropicKey)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(1)
                .build();
        if (claude == null && !blank(openaiKey)) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(5));
            factory.setReadTimeout(Duration.ofSeconds(60));
            this.openai = RestClient.builder().requestFactory(factory).baseUrl(openaiBase)
                    .defaultHeader("Authorization", "Bearer " + openaiKey).build();
        } else {
            this.openai = null;
        }
        this.openaiModel = blank(openaiModel) ? OPENAI_DEFAULT_MODEL : openaiModel;
    }

    /** 키 없이 규칙으로만 (테스트, 감지만 할 때) */
    static AiAdvisor offline() {
        return new AiAdvisor(null, null, null, "http://localhost");
    }

    public boolean enabled() {
        return claude != null || openai != null;
    }

    // --- 설정 키 분류 ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfigKeys(List<ConfigKey> keys) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfigKey(
            @JsonPropertyDescription("입력으로 받은 환경변수 이름 그대로") String env,
            @JsonPropertyDescription("generate / default / input 중 하나") String kind,
            @JsonPropertyDescription("kind 가 default 일 때 넣을 값. 그 외에는 빈 문자열") String value,
            @JsonPropertyDescription("사용자에게 보일 한국어 한 줄 설명. 어디서 받는 값인지, 단위가 무엇인지") String hint) {
    }

    private static final Map<String, Object> CONFIG_SCHEMA = object(Map.of(
            "keys", Map.of("type", "array", "items", object(Map.of(
                    "env", Map.of("type", "string"),
                    "kind", Map.of("type", "string", "enum", List.of("generate", "default", "input")),
                    "value", Map.of("type", "string"),
                    "hint", Map.of("type", "string"))))));

    /** @param facts 키마다 이름, Spring 키, 찾은 파일과 줄, 예시 값 */
    public Optional<ConfigKeys> classify(String facts) {
        return ask("""
                배포 플랫폼이 사용자의 앱을 컨테이너로 띄우기 전에, 앱이 기동할 때 읽는 설정 키를 정리한다.
                각 키를 generate / default / input 중 하나로 나누고, 사용자에게 보일 한국어 설명을 단다.
                - generate: 앱 안에서만 쓰는 비밀값이라 아무 랜덤 문자열이면 된다 (JWT secret, 세션 서명 키, 암호화 키)
                - default: 일반 설정이고 합리적인 기본값이 있다. value 에 그 값을 넣는다.
                  예시 파일 값이 플레이스홀더(your_..., xxx, changeme)가 아니면 그 값을 쓴다.
                  JWT 만료 같은 시간 값은 코드에서 단위를 보고 정한다 (밀리초면 3600000)
                - input: 카카오·네이버·OpenAI·결제·메일 같은 외부 서비스 키, 또는 사용자만 아는 주소. value 는 비운다
                입력에 있는 키만, 같은 env 이름으로 답한다.

                """ + facts, ConfigKeys.class, "config_keys", CONFIG_SCHEMA);
    }

    // --- 실패 진단 ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FailureAdvice(
            @JsonPropertyDescription("배포가 실패한 원인. 사용자에게 보일 한국어 한두 문장") String cause,
            List<FailureFix> fixes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FailureFix(
            @JsonPropertyDescription("env / port / database / healthPath 중 하나") String type,
            @JsonPropertyDescription("type 이 env 일 때 환경변수 이름. 그 외에는 빈 문자열") String env,
            @JsonPropertyDescription("넣을 값. 사용자만 아는 값이면 빈 문자열") String value,
            @JsonPropertyDescription("사용자에게 보일 한국어 한 줄 설명") String hint) {
    }

    private static final Map<String, Object> FAILURE_SCHEMA = object(Map.of(
            "cause", Map.of("type", "string"),
            "fixes", Map.of("type", "array", "items", object(Map.of(
                    "type", Map.of("type", "string", "enum", List.of("env", "port", "database", "healthPath")),
                    "env", Map.of("type", "string"),
                    "value", Map.of("type", "string"),
                    "hint", Map.of("type", "string"))))));

    /** @param context 앱 정보와 값을 가린 실패 로그 */
    public Optional<FailureAdvice> diagnose(String context) {
        return ask("""
                배포 플랫폼에서 사용자의 앱 배포가 실패했다. 로그를 보고 원인과, 플랫폼이 설정만 바꿔 고칠 수 있는 방법을 답한다.
                코드 수정이 필요하면 fixes 는 비우고 cause 에 무엇을 고쳐야 하는지 한국어로 적는다.
                플랫폼이 바꿀 수 있는 것: 환경변수(env), 컨테이너 포트(port), DB 연결(database: postgres/mysql),
                헬스 체크 경로(healthPath, tcp 면 포트 열림만 확인).
                로그의 *** 는 가린 값이다. 추측한 원인이면 그렇다고 적는다.

                """ + context, FailureAdvice.class, "failure_advice", FAILURE_SCHEMA);
    }

    private <T> Optional<T> ask(String prompt, Class<T> type, String name, Map<String, Object> schema) {
        try {
            if (claude != null) {
                StructuredMessageCreateParams<T> params = MessageCreateParams.builder()
                        .model(CLAUDE_MODEL)
                        .maxTokens(4000L)
                        .outputConfig(type)
                        .addUserMessage(prompt)
                        .build();
                return claude.messages().create(params).content().stream()
                        .flatMap(block -> block.text().stream())
                        .map(text -> text.text())
                        .findFirst();
            }
            if (openai != null) {
                JsonNode response = openai.post().uri("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "model", openaiModel,
                                "messages", List.of(Map.of("role", "user", "content", prompt)),
                                "response_format", Map.of("type", "json_schema", "json_schema",
                                        Map.of("name", name, "strict", true, "schema", schema))))
                        .retrieve()
                        .body(JsonNode.class);
                String content = response == null ? null : response.path("choices").path(0).path("message").path("content").asText(null);
                return content == null ? Optional.empty() : Optional.of(JSON.readValue(content, type));
            }
        } catch (Exception e) {
            log.warn("ai call failed, falling back to rules: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /** OpenAI strict 스키마: 모든 속성 필수, 추가 속성 없음 */
    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties,
                "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
