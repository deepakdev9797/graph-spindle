package io.graphspindle.ingestion.validation;

import io.graphspindle.common.ClaimKind;
import io.graphspindle.common.Origin;
import io.graphspindle.common.Polarity;
import io.graphspindle.vocabulary.ValueType;
import java.util.Objects;

/**
 * An ACTIVE claim offered to the reconcile stage under an opaque claim handle ({@code c1} …).
 *
 * @param objectEntityId object id for RELATION claims, else null
 * @param valueType value type for ATTRIBUTE claims, else null
 * @param valueNorm normalized value for ATTRIBUTE claims, else null
 */
public record ClaimCandidate(String handle, String claimId, String subjectEntityId, String predicate,
        ClaimKind kind, String objectEntityId, ValueType valueType, String valueNorm, Polarity polarity,
        Origin origin) {

    public ClaimCandidate {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(subjectEntityId, "subjectEntityId");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(polarity, "polarity");
        Objects.requireNonNull(origin, "origin");
    }
}
