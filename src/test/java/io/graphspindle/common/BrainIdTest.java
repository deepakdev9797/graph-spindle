package io.graphspindle.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BrainIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "0", "dev", "my-brain", "a1-b2-c3", "abc123", "9lives",
            // exactly 63 characters
            "a23456789012345678901234567890123456789012345678901234567890123"})
    void validIds(String id) {
        assertThat(BrainScope.isValid(id)).isTrue();
        assertThat(BrainScope.of(id).brainId()).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "admin", "system", "all", "_x", "_internal", "-dev", "dev-", "Dev", "DEV",
            "my_brain", "my brain", "my.brain", "dev\n", "a/b", "brain!",
            // 64 characters
            "a234567890123456789012345678901234567890123456789012345678901234",
            // unicode lookalikes: Cyrillic 'а', Greek omicron, full-width 'ｄ', dotless i, non-breaking hyphen
            "аdmin", "devο", "ｄev", "ıd", "my‑brain", "dev​"})
    void invalidIds(String id) {
        assertThat(BrainScope.isValid(id)).isFalse();
        assertThatThrownBy(() -> BrainScope.of(id)).isInstanceOf(GraphSpindleException.class)
                .extracting(e -> ((GraphSpindleException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @org.junit.jupiter.api.Test
    void nullIsInvalid() {
        assertThat(BrainScope.isValid(null)).isFalse();
        assertThat(BrainScope.parse(null)).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void uuidV7IsTimeOrderedAndVersioned() {
        java.util.UUID a = Ids.uuidV7(1_000L);
        java.util.UUID b = Ids.uuidV7(2_000L);
        assertThat(a.version()).isEqualTo(7);
        assertThat(a.variant()).isEqualTo(2);
        assertThat(a.toString().compareTo(b.toString())).isNegative();
        assertThat(Ids.newId()).matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }
}
