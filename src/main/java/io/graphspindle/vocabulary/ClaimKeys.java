package io.graphspindle.vocabulary;

import io.graphspindle.common.ClaimKind;
import io.graphspindle.common.Hashing;
import io.graphspindle.common.Polarity;
import java.util.Objects;
import java.util.Optional;

/**
 * Claim slot keys (SCHEMA §7.4). Both keys are written only while a claim is ACTIVE.
 *
 * <pre>
 * objPart   = RELATION ? objectId : valueType + "|" + valueNorm
 * activeKey = sha256hex(brainId ␟ subjectId ␟ predicate ␟ objPart ␟ polarity)
 * slotKey   = sha256hex(brainId ␟ subjectId ␟ predicate)   // only cardinality ONE and POSITIVE
 * </pre>
 */
public final class ClaimKeys {

    private static final String SEP = "\u001f";

    private ClaimKeys() {
    }

    /** The object part of a relation claim. */
    public static String relationObject(String objectId) {
        return requireText(objectId, "objectId");
    }

    /** The object part of an attribute claim. {@code valueNorm} must already be normalized. */
    public static String attributeObject(ValueType valueType, String valueNorm) {
        Objects.requireNonNull(valueType, "valueType");
        return valueType.name() + "|" + Objects.requireNonNull(valueNorm, "valueNorm");
    }

    public static String objectPart(ClaimKind kind, String objectId, ValueType valueType, String valueNorm) {
        return kind == ClaimKind.RELATION ? relationObject(objectId) : attributeObject(valueType, valueNorm);
    }

    public static String activeKey(String brainId, String subjectId, String predicate, String objectPart,
            Polarity polarity) {
        return Hashing.sha256Hex(requireText(brainId, "brainId") + SEP + requireText(subjectId, "subjectId") + SEP
                + requireText(predicate, "predicate") + SEP + requireText(objectPart, "objectPart") + SEP
                + Objects.requireNonNull(polarity, "polarity").name());
    }

    /** The slot key; present only for cardinality ONE and POSITIVE polarity. */
    public static Optional<String> slotKey(String brainId, String subjectId, String predicate, Cardinality cardinality,
            Polarity polarity) {
        if (cardinality != Cardinality.ONE || polarity != Polarity.POSITIVE) {
            return Optional.empty();
        }
        return Optional.of(Hashing.sha256Hex(requireText(brainId, "brainId") + SEP
                + requireText(subjectId, "subjectId") + SEP + requireText(predicate, "predicate")));
    }

    private static String requireText(String s, String name) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return s;
    }
}
