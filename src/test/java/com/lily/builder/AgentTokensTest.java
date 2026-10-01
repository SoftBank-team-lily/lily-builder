package com.lily.builder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTokensTest {

    private final AgentTokens tokens = new AgentTokens(new AgentProperties("x".repeat(32), 25));

    @Test
    void 발급한_토큰은_그_key_로_확인된다() {
        AgentTokens.Issued issued = tokens.issue();

        assertThat(issued.key()).matches("[0-9a-f]{12}");
        assertThat(issued.token()).startsWith(issued.key() + ".");
        assertThat(tokens.verify(issued.token())).contains(issued.key());
    }

    @Test
    void 서명을_바꾸거나_key_를_바꾸면_거절한다() {
        AgentTokens.Issued issued = tokens.issue();
        String other = tokens.issue().key();

        assertThat(tokens.verify(issued.token() + "x")).isEmpty();
        assertThat(tokens.verify(other + issued.token().substring(12))).isEmpty();
        assertThat(tokens.verify("no-dot")).isEmpty();
        assertThat(tokens.verify(null)).isEmpty();
    }

    @Test
    void 다른_서명_키로_만든_토큰은_거절한다() {
        AgentTokens.Issued issued = new AgentTokens(new AgentProperties("y".repeat(32), 25)).issue();

        assertThat(tokens.verify(issued.token())).isEmpty();
    }

    @Test
    void 서명_키가_짧으면_꺼진다() {
        AgentTokens off = new AgentTokens(new AgentProperties("short", 25));

        assertThat(off.enabled()).isFalse();
        assertThat(off.verify("abcdefabcdef.x")).isEmpty();
        assertThatThrownBy(off::issue).isInstanceOf(IllegalStateException.class);
    }
}
