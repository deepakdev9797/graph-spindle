package io.graphspindle.ingestion.validation;

import io.graphspindle.common.ClaimKind;
import io.graphspindle.common.PartialDate;
import io.graphspindle.common.Polarity;
import io.graphspindle.vocabulary.Cardinality;
import io.graphspindle.vocabulary.ClaimKeys;
import io.graphspindle.vocabulary.ValueType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A fully validated claim, ready to be written. Keys need real ids, so they are computed with
 * {@link #keys(String, String, String)} at apply time, once new entities have ids.
 *
 * @param contradictsClaimId id of an ACTIVE candidate claim this one contradicts (same ONE slot or
 *     opposite polarity); routed by the conflict policy at apply time; null if none
 * @param reinforcesClaimId id of an ACTIVE candidate claim with the same triple and polarity; null if none
 */
public record ResolvedClaim(
        ResolvedEntity subject,
        String predicate,
        ClaimKind kind,
        Cardinality cardinality,
        ResolvedEntity object,
        ValueType valueType,
        String value,
        String valueNorm,
        Polarity polarity,
        String statement,
        String evidence,
        double confidence,
        PartialDate validFrom,
        PartialDate validTo,
        List<Integer> sources,
        String contradictsClaimId,
        String reinforcesClaimId) {

    public ResolvedClaim {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(cardinality, "cardinality");
        Objects.requireNonNull(polarity, "polarity");
        if (kind == ClaimKind.RELATION ? object == null : valueNorm == null || valueType == null) {
            throw new IllegalArgumentException("relation claims need an object, attribute claims a value");
        }
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    ResolvedClaim withAnnotations(String contradicts, String reinforces) {
        return new ResolvedClaim(subject, predicate, kind, cardinality, object, valueType, value, valueNorm, polarity,
                statement, evidence, confidence, validFrom, validTo, sources, contradicts, reinforces);
    }

    /** The claim keys (SCHEMA §7.4) once subject (and object) ids are known. */
    public Keys keys(String brainId, String subjectId, String objectId) {
        String objPart = ClaimKeys.objectPart(kind, objectId, valueType, valueNorm);
        return new Keys(ClaimKeys.activeKey(brainId, subjectId, predicate, objPart, polarity),
                ClaimKeys.slotKey(brainId, subjectId, predicate, cardinality, polarity));
    }

    /** {@code activeKey} and the optional {@code slotKey}. */
    public record Keys(String activeKey, Optional<String> slotKey) {
    }
}
