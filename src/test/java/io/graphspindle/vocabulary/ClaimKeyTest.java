package io.graphspindle.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.graphspindle.common.ClaimKind;
import io.graphspindle.common.Polarity;
import org.junit.jupiter.api.Test;

class ClaimKeyTest {

    private static final String OBJ = ClaimKeys.relationObject("obj-1");

    @Test
    void activeKeyIsDeterministicSha256Hex() {
        String a = ClaimKeys.activeKey("dev", "subj-1", "USES", OBJ, Polarity.POSITIVE);
        String b = ClaimKeys.activeKey("dev", "subj-1", "USES", OBJ, Polarity.POSITIVE);
        assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void activeKeyIsPolaritySensitive() {
        assertThat(ClaimKeys.activeKey("dev", "s", "USES", OBJ, Polarity.POSITIVE))
                .isNotEqualTo(ClaimKeys.activeKey("dev", "s", "USES", OBJ, Polarity.NEGATIVE));
    }

    @Test
    void activeKeyDependsOnEveryPart() {
        String base = ClaimKeys.activeKey("dev", "s", "USES", OBJ, Polarity.POSITIVE);
        assertThat(ClaimKeys.activeKey("other", "s", "USES", OBJ, Polarity.POSITIVE)).isNotEqualTo(base);
        assertThat(ClaimKeys.activeKey("dev", "s2", "USES", OBJ, Polarity.POSITIVE)).isNotEqualTo(base);
        assertThat(ClaimKeys.activeKey("dev", "s", "WRITTEN_IN", OBJ, Polarity.POSITIVE)).isNotEqualTo(base);
        assertThat(ClaimKeys.activeKey("dev", "s", "USES", "obj-2", Polarity.POSITIVE)).isNotEqualTo(base);
    }

    @Test
    void separatorPreventsConcatenationCollisions() {
        assertThat(ClaimKeys.activeKey("dev", "ab", "C", OBJ, Polarity.POSITIVE))
                .isNotEqualTo(ClaimKeys.activeKey("dev", "a", "bC", OBJ, Polarity.POSITIVE));
    }

    @Test
    void objectPartDistinguishesRelationAndAttributeAndValueType() {
        assertThat(ClaimKeys.objectPart(ClaimKind.RELATION, "x", null, null)).isEqualTo("x");
        assertThat(ClaimKeys.objectPart(ClaimKind.ATTRIBUTE, null, ValueType.ENUM, "ACTIVE")).isEqualTo("ENUM|ACTIVE");
        assertThat(ClaimKeys.attributeObject(ValueType.STRING, "1"))
                .isNotEqualTo(ClaimKeys.attributeObject(ValueType.NUMBER, "1"));
    }

    @Test
    void slotKeyOnlyForCardinalityOneAndPositive() {
        assertThat(ClaimKeys.slotKey("dev", "s", "LICENSED_UNDER", Cardinality.ONE, Polarity.POSITIVE)).isPresent();
        assertThat(ClaimKeys.slotKey("dev", "s", "USES", Cardinality.MANY, Polarity.POSITIVE)).isEmpty();
        assertThat(ClaimKeys.slotKey("dev", "s", "LICENSED_UNDER", Cardinality.ONE, Polarity.NEGATIVE)).isEmpty();
    }

    @Test
    void slotKeyIgnoresObjectSoDifferentValuesCollide() {
        // two licenses for the same subject share the slot: that is what the DB constraint enforces
        String mit = ClaimKeys.slotKey("dev", "s", "LICENSED_UNDER", Cardinality.ONE, Polarity.POSITIVE).orElseThrow();
        String gpl = ClaimKeys.slotKey("dev", "s", "LICENSED_UNDER", Cardinality.ONE, Polarity.POSITIVE).orElseThrow();
        assertThat(mit).isEqualTo(gpl).hasSize(64);
        assertThat(ClaimKeys.slotKey("other", "s", "LICENSED_UNDER", Cardinality.ONE, Polarity.POSITIVE))
                .get().isNotEqualTo(mit);
        assertThat(mit).isNotEqualTo(ClaimKeys.activeKey("dev", "s", "LICENSED_UNDER", OBJ, Polarity.POSITIVE));
    }

    @Test
    void attributeValueNormUsesTypeRules() {
        AttributePredicateDef category = new VocabularyRegistry().core().attribute("category", "Technology")
                .orElseThrow();
        assertThat(AttributeValues.check(category, " language ").valueNorm()).isEqualTo("LANGUAGE");
        AttributePredicateDef role = new VocabularyRegistry().core().attribute("role", "Person").orElseThrow();
        assertThat(AttributeValues.check(role, "  The Backend  Engineer ").valueNorm()).isEqualTo("backend engineer");
    }

    @Test
    void requiresAllParts() {
        assertThatThrownBy(() -> ClaimKeys.activeKey("", "s", "P", OBJ, Polarity.POSITIVE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClaimKeys.relationObject(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
