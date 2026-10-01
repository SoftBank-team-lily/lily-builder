package com.lily.builder;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 에이전트 토큰. {@code {key}.{서명}} 형식이라 저장하지 않고 서명만으로 확인한다.
 *
 * <p>key 는 에이전트의 이름표다. lily-frontend 가 사용자별로 보관하고, 배포를 보낼 때 이 key 로 에이전트를 고른다.
 * 토큰 전체는 발급할 때 한 번만 돌려준다 (사용자 PC 의 에이전트 실행 명령에 들어간다).
 * 서명 키(AGENT_TOKEN_SECRET)를 바꾸면 이전 토큰은 모두 무효가 된다. 개별 폐기는 아직 없다.
 */
@Component
public class AgentTokens {

    static final Pattern KEY = Pattern.compile("[0-9a-f]{12}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgentProperties props;

    public AgentTokens(AgentProperties props) {
        this.props = props;
    }

    public boolean enabled() {
        return props.enabled();
    }

    public Issued issue() {
        if (!enabled()) {
            throw new IllegalStateException("AGENT_TOKEN_SECRET 이 없어 에이전트 토큰을 만들 수 없다");
        }
        byte[] random = new byte[6];
        RANDOM.nextBytes(random);
        String key = HexFormat.of().formatHex(random);
        return new Issued(key, key + "." + sign(key));
    }

    /** 서명이 맞으면 key */
    public Optional<String> verify(String token) {
        if (!enabled() || token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String key = token.substring(0, dot);
        if (!KEY.matcher(key).matches()) {
            return Optional.empty();
        }
        byte[] expected = sign(key).getBytes(StandardCharsets.US_ASCII);
        byte[] given = token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given) ? Optional.of(key) : Optional.empty();
    }

    private String sign(String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.tokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(("lily-agent:" + key).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @param token 에이전트 실행에 넣는 값. 다시 보여줄 수 없다 */
    public record Issued(String key, String token) {
    }
}
