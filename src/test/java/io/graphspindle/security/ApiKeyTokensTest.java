package io.graphspindle.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApiKeyTokensTest {

    @Test
    void generatedTokensParseAndHashTheSecretOnly() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            ApiKeyTokens.Generated g = ApiKeyTokens.generate();
            assertThat(g.token()).startsWith("gsk_" + g.keyId() + "_").hasSize(4 + 16 + 1 + 43);
            ApiKeyTokens.Parsed p = ApiKeyTokens.parse(g.token()).orElseThrow();
            assertThat(p.keyId()).isEqualTo(g.keyId());
            assertThat(ApiKeyTokens.sha256Hex(p.secret())).isEqualTo(g.secretHash());
            assertThat(g.secretHash()).doesNotContain(p.secret()).hasSize(64);
            assertThat(seen.add(g.keyId())).isTrue();
            assertThat(g.toString()).doesNotContain(p.secret());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "gsk_", "gsk_0123456789abcdef_short", "gsk_0123456789ABCDEF_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "xsk_0123456789abcdef_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "gsk_0123456789abcdef_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ",
        "gsk_0123456789abcdef_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa+"})
    void malformedTokensAreRejected(String token) {
        assertThat(ApiKeyTokens.parse(token)).isEmpty();
    }
}
