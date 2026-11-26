package io.graphspindle.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class AttributeValuesTest {

    private final BrainVocabulary core = new VocabularyRegistry().core();

    private AttributeValues.Checked check(String name, String type, String value) {
        return AttributeValues.check(core.attribute(name, type).orElseThrow(), value);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "status   | Project    | active                 | ACTIVE",
            "status   | Task       | in progress            | IN_PROGRESS",
            "status   | Task       | in-progress            | IN_PROGRESS",
            "category | Standard   | License                | LICENSE",
            "status   | Project    | FINISHED               | NULL",
            "decidedOn| Decision   | 2025-03                | 2025-03",
            "decidedOn| Decision   | 2025-02-30             | NULL",
            "decidedOn| Decision   | March 2025             | NULL",
            "homepage | Project    | https://Example.com/x/ | https://Example.com/x/",
            "homepage | Project    | ftp://example.com      | NULL",
            "homepage | Project    | javascript:alert(1)    | NULL",
            "homepage | Project    | file:/tmp/notes.md     | file:/tmp/notes.md",
            "version  | Technology | 21                     | 21",
            "role     | Person     | ''                     | NULL"})
    void checksValues(String name, String type, String raw, String expected) {
        AttributeValues.Checked c = check(name, type, raw);
        if (expected == null) {
            assertThat(c.ok()).isFalse();
        } else {
            assertThat(c.ok()).isTrue();
            assertThat(c.value()).isEqualTo(expected);
        }
    }

    @Test
    void urlNormIsLowercasedHost() {
        assertThat(check("homepage", "Project", "HTTPS://Example.COM/x/").valueNorm()).isEqualTo("https://example.com/x");
    }

    @Test
    void stringLengthIsBounded() {
        assertThat(check("version", "Technology", "1".repeat(51)).ok()).isFalse();
        assertThat(check("version", "Technology", "1".repeat(50)).ok()).isTrue();
    }

    @Test
    void decisionSupersededIsEngineOnly() {
        AttributeValues.Checked c = check("status", "Decision", "superseded");
        assertThat(c.engineOnly()).isTrue();
        assertThat(c.ok()).isFalse();
    }

    @Test
    void numbersAndBooleans() {
        AttributePredicateDef servings = new AttributePredicateDef("servings", "d", Set.of("Recipe"), ValueType.NUMBER,
                List.of(), null, BigDecimal.ONE, BigDecimal.valueOf(100), Cardinality.ONE, Set.of(), false);
        assertThat(AttributeValues.check(servings, "4.50").value()).isEqualTo("4.5");
        assertThat(AttributeValues.check(servings, "1E1").value()).isEqualTo("10");
        assertThat(AttributeValues.check(servings, "0").ok()).isFalse();
        assertThat(AttributeValues.check(servings, "lots").ok()).isFalse();
        AttributePredicateDef vegan = new AttributePredicateDef("vegan", "d", Set.of("Recipe"), ValueType.BOOLEAN,
                List.of(), null, null, null, Cardinality.ONE, Set.of(), false);
        assertThat(AttributeValues.check(vegan, "TRUE").value()).isEqualTo("true");
        assertThat(AttributeValues.check(vegan, "yes").ok()).isFalse();
    }
}
