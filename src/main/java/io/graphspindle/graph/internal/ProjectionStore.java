package io.graphspindle.graph.internal;

import io.graphspindle.graph.ClaimView;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Keeps projections in sync with claims (SCHEMA I3, §9.3): a relation edge and an {@code attr_*}
 * property exist iff an ACTIVE POSITIVE claim exists. Always called in the transaction that changed
 * the claim.
 *
 * <p>Projections are derived from <em>stored</em> claims, whose predicate was checked against the
 * brain vocabulary when the claim was written. They are therefore validated as plain identifiers
 * only ({@link Tokens#storedPredicate}), so removing or retracting a claim keeps working after its
 * predicate was disabled or removed from the vocabulary (QA M4 F1).
 */
@Component
public class ProjectionStore {

    private final ClaimStore claims;
    private final EntityStore entities;

    public ProjectionStore(ClaimStore claims, EntityStore entities) {
        this.claims = claims;
        this.entities = entities;
    }

    /** Brings the projection of {@code claim} (as stored now) in line with its status. */
    public void sync(ScopedTx tx, ClaimView claim) {
        if (claim.relation()) {
            boolean shouldExist = claim.active() && "POSITIVE".equals(claim.polarity());
            if (shouldExist) {
                tx.run(ProjectionStatements.PROJECT_RELATION, P.of("claimId", claim.id(), "predicate",
                        Tokens.storedPredicate(claim.predicate()))).consume();
            } else {
                tx.run(ProjectionStatements.UNPROJECT_RELATION, P.of("subjectId", claim.subjectId(), "claimId",
                        claim.id())).consume();
            }
        } else {
            recomputeAttribute(tx, claim.subjectId(), claim.predicate());
        }
    }

    /** {@code attr_<predicate>}: the single value (ONE), a sorted list (MANY), or removed when no active value. */
    public void recomputeAttribute(ScopedTx tx, String subjectId, String predicate) {
        String property = "attr_" + Tokens.storedPredicate(predicate);
        List<Object[]> rows = claims.activeAttributeValues(tx, subjectId, predicate);
        Object value = expectedAttribute(rows.stream().map(r -> (String) r[0]).toList(),
                rows.isEmpty() ? null : (String) rows.get(0)[1]);
        entities.setAttributes(tx, subjectId, Collections.singletonMap(property, value));
    }

    /** Shared with ProjectionVerifier so both compute the projection the same way. */
    public static Object expectedAttribute(List<String> sortedValues, String cardinality) {
        if (sortedValues.isEmpty()) {
            return null;
        }
        return "MANY".equals(cardinality) ? List.copyOf(sortedValues) : sortedValues.get(0);
    }
}
