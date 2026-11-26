package io.graphspindle.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PartialDateTest {

    @ParameterizedTest
    @ValueSource(strings = {"2025", "2025-02", "2024-02-29", "0001-01-01"})
    void acceptsValidDates(String s) {
        assertThat(PartialDate.parse(s)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "25", "2025-2", "2025-13", "2023-02-29", "2025-00", "2025/01/01", "２０２５",
            "2025-01-01T00:00", "abcd", " 2025"})
    void rejectsInvalidDates(String s) {
        assertThat(PartialDate.parse(s)).isEmpty();
        assertThatThrownBy(() -> new PartialDate(s)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sortKeyPadsMissingParts() {
        assertThat(new PartialDate("2025").sortKey()).isEqualTo("2025-01-01");
        assertThat(new PartialDate("2025-06").sortKey()).isEqualTo("2025-06-01");
        assertThat(new PartialDate("2025").compareTo(new PartialDate("2024-12-31"))).isPositive();
    }

    @Test
    void hashingIsSha256Hex() {
        assertThat(Hashing.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
