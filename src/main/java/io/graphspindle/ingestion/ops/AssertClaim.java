package io.graphspindle.ingestion.ops;

import io.graphspindle.common.Polarity;
import java.util.List;
import java.util.Objects;

/**
 * {@code assert_claim}: a relation claim (with {@code object}) or an attribute claim (with
 * {@code value}). Exactly one of the two is set. Also used as the body of {@code supersede_claim}
 * replacements and {@code flag_conflict} incoming claims.
 */
public record AssertClaim(
        EntityTarget subject,
        String predicate,
        EntityTarget object,
        String value,
        Polarity polarity,
        String statement,
        String evidence,
        double confidence,
        String validFrom,
        String validTo,
        List<Integer> sources) implements MutationOperation {

    public static final String OP = "assert_claim";

    public AssertClaim {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(evidence, "evidence");
        if ((object == null) == (value == null)) {
            throw new IllegalArgumentException("exactly one of object or value is required");
        }
        polarity = polarity == null ? Polarity.POSITIVE : polarity;
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** Relation claim towards an entity. */
    public static AssertClaim relation(EntityTarget subject, String predicate, EntityTarget object, String statement,
            String evidence, double confidence) {
        return new AssertClaim(subject, predicate, object, null, Polarity.POSITIVE, statement, evidence, confidence,
                null, null, List.of());
    }

    /** Attribute claim with a literal value. */
    public static AssertClaim attribute(EntityTarget subject, String predicate, String value, String statement,
            String evidence, double confidence) {
        return new AssertClaim(subject, predicate, null, value, Polarity.POSITIVE, statement, evidence, confidence,
                null, null, List.of());
    }

    public boolean isRelation() {
        return object != null;
    }

    @Override
    public String wireName() {
        return OP;
    }
}
