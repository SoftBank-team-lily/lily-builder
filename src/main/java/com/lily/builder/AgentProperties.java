package com.lily.builder;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 온프레미스 에이전트(lily-on-premise) 연결 설정.
 *
 * @param tokenSecret 에이전트 토큰 서명 키. 32자 미만이면 에이전트 기능을 끈다
 * @param pingSeconds 연결을 유지하려고 보내는 ping 주기. ALB 유휴 제한(60초)보다 짧게
 */
@ConfigurationProperties("lily.builder.agents")
public record AgentProperties(
        @DefaultValue("") String tokenSecret,
        @DefaultValue("25") long pingSeconds) {

    public boolean enabled() {
        return tokenSecret != null && tokenSecret.length() >= 32;
    }
}
