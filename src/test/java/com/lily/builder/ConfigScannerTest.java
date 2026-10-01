package com.lily.builder;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigScannerTest {

    /** book-club 백엔드: application.yml 은 .gitignore, 예시 파일만 있다 */
    private static final Map<String, String> BOOK_CLUB = Map.of(
            "pom.xml", "<artifactId>spring-boot-starter-data-jpa</artifactId><artifactId>postgresql</artifactId>",
            "src/main/resources/application-example.yml", """
                    spring:
                      datasource:
                        url: jdbc:postgresql://localhost:5432/bookclub
                      jpa:
                        hibernate:
                          ddl-auto: update
                    jwt:
                      secret: your_jwt_secret_key
                      expiration: 3600000
                    kakao:
                      api-key: your_kakao_api_key
                    """,
            "src/main/java/bookclub/global/jwt/JwtProvider.java", """
                    public JwtProvider(@Value("${jwt.secret}") String secret,
                                       @Value("${jwt.expiration}") long expiration) {}
                    """,
            "src/main/java/bookclub/book/client/KakaoBookClient.java", """
                    public KakaoBookClient(@Value("${kakao.rest-api-key}") String restApiKey) {}
                    """,
            "src/main/java/bookclub/book/client/NaverBookClient.java", """
                    @Value("${naver.client-id:}") String clientId,
                    """,
            "src/main/java/bookclub/book/service/AiBookTagService.java", """
                    @Value("${ai.openai.api-key}") String apiKey,
                    @Value("${ai.openai.model}") String model
                    """,
            "src/main/java/bookclub/Db.java", """
                    @Value("${spring.datasource.url}") String url;
                    """);

    @Test
    void 기본값_없는_Value_키를_환경변수로_찾고_플랫폼_값과_기본값_있는_키는_뺀다() {
        ConfigScanner.Result result = ConfigScanner.scan(List.copyOf(BOOK_CLUB.keySet()), BOOK_CLUB::get);

        assertThat(result.keys()).extracting(ConfigScanner.Key::env).containsExactlyInAnyOrder(
                "JWT_SECRET", "JWT_EXPIRATION", "KAKAO_REST_API_KEY", "AI_OPENAI_API_KEY", "AI_OPENAI_MODEL",
                "SPRING_JPA_HIBERNATE_DDL_AUTO");
        assertThat(result.jpa()).isTrue();
        assertThat(ConfigScanner.wanted(List.copyOf(BOOK_CLUB.keySet()))).containsAll(BOOK_CLUB.keySet());
    }

    @Test
    void 키마다_채울_방법을_정한다() {
        ConfigScanner.Result result = ConfigScanner.scan(List.copyOf(BOOK_CLUB.keySet()), BOOK_CLUB::get);
        Map<String, ConfigAdvisor.Advice> advice = new HashMap<>();
        BuildService.offlineConfig().advise(result).forEach(a -> advice.put(a.env(), a));

        assertThat(advice.get("JWT_SECRET").kind()).isEqualTo(ConfigAdvisor.Kind.GENERATE);
        assertThat(advice.get("JWT_EXPIRATION").kind()).isEqualTo(ConfigAdvisor.Kind.DEFAULT);
        assertThat(advice.get("JWT_EXPIRATION").value()).isEqualTo("3600000");
        assertThat(advice.get("KAKAO_REST_API_KEY").kind()).isEqualTo(ConfigAdvisor.Kind.INPUT);
        assertThat(advice.get("AI_OPENAI_API_KEY").kind()).isEqualTo(ConfigAdvisor.Kind.INPUT);
        assertThat(advice.get("AI_OPENAI_MODEL").value()).isEqualTo("gpt-4o-mini");
        assertThat(advice.get("SPRING_JPA_HIBERNATE_DDL_AUTO").value()).isEqualTo("update");
    }

    @Test
    void 커밋된_설정이_값을_가지면_필요하지_않고_ENV_참조는_필요하다() {
        Map<String, String> files = Map.of(
                "src/main/resources/application.yml", """
                        jwt:
                          secret: ${JWT_SECRET}
                          expiration: 3600000
                        mail:
                          host: ${MAIL_HOST:smtp.example.com}
                        """,
                "src/main/java/A.java", """
                        @Value("${jwt.secret}") String s; @Value("${jwt.expiration}") long e;
                        """);

        ConfigScanner.Result result = ConfigScanner.scan(List.copyOf(files.keySet()), files::get);

        assertThat(result.keys()).extracting(ConfigScanner.Key::env).containsExactly("JWT_SECRET");
    }

    @Test
    void Node_는_대신할_값_없이_읽는_키만_찾는다() {
        Map<String, String> files = Map.of(
                "src/server.ts", """
                        const secret = process.env.SESSION_SECRET;
                        const port = process.env.PORT || 3000;
                        const url = process.env["API_BASE"] ?? "http://localhost";
                        """,
                ".env.example", "SESSION_SECRET=changeme\n");

        ConfigScanner.Result result = ConfigScanner.scan(List.copyOf(files.keySet()), files::get);

        assertThat(result.keys()).singleElement().satisfies(key -> {
            assertThat(key.env()).isEqualTo("SESSION_SECRET");
            assertThat(key.required()).isFalse();
        });
    }

    @Test
    void AI_가_입력으로_봐도_규칙의_기본값은_미리_채운다() {
        okhttp3.mockwebserver.MockWebServer openai = new okhttp3.mockwebserver.MockWebServer();
        try (openai) {
            openai.enqueue(new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody("""
                    {"choices":[{"message":{"content":"{\\"keys\\":[{\\"env\\":\\"AI_OPENAI_MODEL\\",\\"kind\\":\\"input\\",\\"value\\":\\"\\",\\"hint\\":\\"모델 이름\\"}]}"}}]}"""));
            openai.start();
            ConfigAdvisor advisor = new ConfigAdvisor(new AiAdvisor(null, "k", null, openai.url("/").toString().replaceAll("/$", "")));
            ConfigScanner.Result result = new ConfigScanner.Result(List.of(
                    new ConfigScanner.Key("ai.openai.model", "AI_OPENAI_MODEL", "a", null, "", true)), false, false);

            assertThat(advisor.advise(result)).singleElement().satisfies(advice -> {
                assertThat(advice.kind()).isEqualTo(ConfigAdvisor.Kind.DEFAULT);
                assertThat(advice.value()).isEqualTo("gpt-4o-mini");
                assertThat(advice.hint()).isEqualTo("모델 이름");
            });
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void 환경변수_이름은_Spring_이_읽는_형태다() {
        assertThat(ConfigScanner.envName("kakao.rest-api-key")).isEqualTo("KAKAO_REST_API_KEY");
        assertThat(ConfigScanner.envName("app.items[0].name")).isEqualTo("APP_ITEMS_0_NAME");
    }
}
