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
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * diff 한 편만 받는 호출. 배포 실패 진단({@link AiAdvisor})과 따로다.
 * 키가 없거나 호출이 실패하면 빈 diff 라서 PR 이 열리지 않는다.
 *
 * <p>시험 경로는 Groq 이다. {@code GROQ_API_KEY} 가 있으면
 * {@code https://api.groq.com/openai/v1/chat/completions} 로 {@code openai/gpt-oss-20b} 를 부른다.
 * 그 키가 없을 때만 Claude, 그다음 OpenAI 다.
 */
public final class AiPatchModel implements PatchModel {

    private static final Logger log = LoggerFactory.getLogger(AiPatchModel.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String GROQ_BASE = "https://api.groq.com/openai";
    /** strict JSON 스키마를 받는 Groq 모델. 무료 한도 안에서 초안을 만든다. */
    static final String GROQ_DEFAULT_MODEL = "openai/gpt-oss-20b";

    private final AnthropicClient claude;
    private final RestClient openai;
    private final String openaiModel;

    /** 환경 변수로 고른다. Groq 키가 있으면 유료 키보다 앞이다. */
    public static AiPatchModel fromEnvironment() {
        String groq = System.getenv("GROQ_API_KEY");
        if (!blank(groq)) {
            String model = System.getenv("GROQ_MODEL");
            return new AiPatchModel(null, groq, blank(model) ? GROQ_DEFAULT_MODEL : model, GROQ_BASE);
        }
        return new AiPatchModel(System.getenv("ANTHROPIC_API_KEY"), System.getenv("OPENAI_API_KEY"),
                System.getenv("OPENAI_MODEL"), "https://api.openai.com");
    }

    public AiPatchModel(String anthropicKey, String openaiKey, String openaiModel, String openaiBase) {
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
        this.openaiModel = blank(openaiModel) ? AiAdvisor.OPENAI_DEFAULT_MODEL : openaiModel;
    }

    @Override
    public Optional<String> diff(String prompt) {
        try {
            if (claude != null) {
                StructuredMessageCreateParams<PatchDiff> params = MessageCreateParams.builder()
                        .model(AiAdvisor.CLAUDE_MODEL)
                        .maxTokens(4000L)
                        .outputConfig(PatchDiff.class)
                        .addUserMessage(prompt)
                        .build();
                return claude.messages().create(params).content().stream()
                        .flatMap(block -> block.text().stream())
                        .map(text -> text.text().diff())
                        .findFirst();
            }
            if (openai != null) {
                JsonNode response = openai.post().uri("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "model", openaiModel,
                                "messages", List.of(Map.of("role", "user", "content", prompt)),
                                "response_format", Map.of("type", "json_schema", "json_schema", Map.of(
                                        "name", "patch_diff", "strict", true, "schema", Map.of(
                                                "type", "object",
                                                "properties", Map.of("diff", Map.of("type", "string")),
                                                "required", List.of("diff"),
                                                "additionalProperties", false)))))
                        .retrieve()
                        .body(JsonNode.class);
                String content = response == null ? null
                        : response.path("choices").path(0).path("message").path("content").asText(null);
                if (content == null) {
                    return Optional.empty();
                }
                return Optional.ofNullable(JSON.readValue(content, PatchDiff.class).diff());
            }
        } catch (Exception e) {
            log.warn("patch model failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PatchDiff(
            @JsonPropertyDescription("unified diff. 고칠 수 없으면 빈 문자열") String diff) {
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
