package io.graphspindle.graph.internal;

import io.graphspindle.common.Ids;
import io.graphspindle.common.Normalizer;
import io.graphspindle.common.Origin;
import io.graphspindle.common.Polarity;
import io.graphspindle.graph.ApplyResult.Outcome;
import io.graphspindle.graph.ClaimView;
import io.graphspindle.graph.EntityView;
import io.graphspindle.vocabulary.AttributePredicateDef;
import io.graphspindle.vocabulary.BrainVocabulary;
import io.graphspindle.vocabulary.ClaimKeys;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Engine consequence rules, run in the transaction that made claims ACTIVE: by a plan
 * ({@code GraphStore}) and by conflict resolution ({@code ACCEPT_INCOMING}, {@code KEEP_BOTH};
 * ADR-030). Today one rule: SCHEMA §10.3, an ACTIVE {@code REPLACES} claim between decisions sets the
 * replaced decision's {@code status} to the engine-only value {@code SUPERSEDED} with a SYSTEM claim.
 * A MANUAL status claim is never overridden (PROTECTED_OVERRIDE conflict instead).
 */
public final class ConsequenceRules {

    /** Receives what a rule did. */
    @FunctionalInterface
    public interface Sink {
        void outcome(Outcome outcome, String entityId, String claimId, String conflictId);
    }

    private final EntityStore entities;
    private final ClaimStore claims;
    private final ProjectionStore projections;
    private final ConflictStore conflicts;

    public ConsequenceRules(EntityStore entities, ClaimStore claims, ProjectionStore projections,
            ConflictStore conflicts) {
        this.entities = entities;
        this.claims = claims;
        this.projections = projections;
        this.conflicts = conflicts;
    }

    /**
     * Applies the rules to the claims that became ACTIVE.
     *
     * @param ingestionId the ingestion that caused it, or null (manual write, conflict resolution)
     */
    public void apply(ScopedTx tx, BrainVocabulary vocabulary, Collection<ClaimView> activated, String ingestionId,
            String principal, Sink sink) {
        Set<String> replaced = new LinkedHashSet<>();
        for (ClaimView c : activated) {
            if ("REPLACES".equals(c.predicate()) && c.relation() && "POSITIVE".equals(c.polarity())) {
                replaced.add(c.objectId());
            }
        }
        for (String decisionId : replaced) {
            EntityView decision = entities.findById(tx, decisionId).orElseThrow();
            Optional<AttributePredicateDef> status = vocabulary.attribute("status", decision.type());
            if (status.isEmpty() || !status.get().enumValues().contains("SUPERSEDED")) {
                continue;
            }
            String slot = ClaimKeys.slotKey(tx.brainId(), decisionId, "status", status.get().cardinality(),
                    Polarity.POSITIVE).orElse(null);
            Optional<ClaimView> current = slot == null ? Optional.empty() : claims.findBySlotKey(tx, slot);
            if (current.isPresent() && "SUPERSEDED".equals(current.get().valueNorm())) {
                continue;
            }
            if (current.isPresent() && Origin.MANUAL.name().equals(current.get().origin())) {
                String conflictId = conflicts.create(tx, "PROTECTED_OVERRIDE",
                        "a replacing decision would set the MANUAL status of this decision to SUPERSEDED",
                        ingestionId);
                conflicts.involveClaim(tx, conflictId, current.get().id(), "EXISTING");
                sink.outcome(Outcome.PROTECTED_OVERRIDE, decisionId, current.get().id(), conflictId);
                continue;
            }
            String statement = Normalizer.truncate("Decision \"" + decision.name()
                    + "\" was superseded by a replacing decision.", 500);
            Map<String, Object> props = P.of("id", Ids.newId(), "kind", "ATTRIBUTE", "predicate", "status",
                    "subjectId", decisionId, "valueType", status.get().valueType().name(), "value", "SUPERSEDED",
                    "valueNorm", "SUPERSEDED", "polarity", "POSITIVE", "statement", statement, "status", "ACTIVE",
                    "confidence", 1.0, "origin", Origin.SYSTEM.name(), "cardinality",
                    status.get().cardinality().name(), "createdByIngestionId", ingestionId,
                    "createdByPrincipal", principal, "evidenceCount", 0);
            current.ifPresent(old -> {
                ClaimView s = claims.leaveActive(tx, old.id(), "SUPERSEDED", "replaced by another decision", null,
                        ingestionId);
                projections.sync(tx, s);
                sink.outcome(Outcome.CLAIM_SUPERSEDED, decisionId, old.id(), null);
            });
            props.put("activeKey", ClaimKeys.activeKey(tx.brainId(), decisionId, "status",
                    ClaimKeys.attributeObject(status.get().valueType(), "SUPERSEDED"), Polarity.POSITIVE));
            props.put("slotKey", slot);
            ClaimView created = claims.create(tx, props);
            projections.sync(tx, created);
            current.ifPresent(old -> claims.supersedes(tx, created.id(), old.id(), "replaced by another decision"));
            sink.outcome(Outcome.CONSEQUENCE_APPLIED, decisionId, created.id(), null);
        }
    }
}
