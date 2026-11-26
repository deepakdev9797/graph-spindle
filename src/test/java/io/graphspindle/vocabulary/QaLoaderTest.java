package io.graphspindle.vocabulary;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class QaLoaderTest {

    @Test
    @Timeout(10)
    void aliasBombIsRejectedQuickly() {
        StringBuilder y = new StringBuilder("profile: core-v1\na: &a [\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\"]\n");
        String prev = "a";
        for (int i = 0; i < 9; i++) {
            String n = "l" + i;
            y.append(n).append(": &").append(n).append(" [");
            for (int j = 0; j < 9; j++) {
                y.append(j == 0 ? "" : ",").append("*").append(prev);
            }
            y.append("]\n");
            prev = n;
        }
        assertThatThrownBy(() -> VocabularyDefinitionLoader.load(
                new ByteArrayInputStream(y.toString().getBytes(StandardCharsets.UTF_8)), "bomb"))
                .isInstanceOf(InvalidVocabularyDefinitionException.class);
    }

    @Test
    void javaTypeTagIsRejected() {
        String y = "profile: !!java.net.URL [\"http://example.invalid\"]\n";
        assertThatThrownBy(() -> VocabularyDefinitionLoader.load(
                new ByteArrayInputStream(y.getBytes(StandardCharsets.UTF_8)), "tag"))
                .isInstanceOf(InvalidVocabularyDefinitionException.class);
    }
}
