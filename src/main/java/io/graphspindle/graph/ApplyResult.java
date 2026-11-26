package io.graphspindle.graph;

import java.util.List;
import java.util.Map;

/**
 * Outcome of {@link GraphStore#apply}.
 *
 * @param status the Ingestion status written: {@code APPLIED} or {@code NO_CHANGES}
 * @param opsApplied distinct operation indexes that changed the graph
 * @param refs local refs of the plan mapped to real entity ids
 * @param attempts 1, or 2 when the transaction was retried after a uniqueness race
 */
public record ApplyResult(String ingestionId, String status, int opsApplied, Map<String, String> refs,
        List<OpOutcome> outcomes, int attempts) {

    public ApplyResult {
        refs = Map.copyOf(refs);
        outcomes = List.copyOf(outcomes);
    }

    public List<OpOutcome> outcomes(Outcome kind) {
        return outcomes.stream().filter(o -> o.outcome() == kind).toList();
    }

    /** What happened for one command (several per operation are possible). opIndex -1 = engine consequence. */
    public record OpOutcome(int opIndex, Outcome outcome, String entityId, String claimId, String conflictId) {
    }

    public enum Outcome {
        ENTITY_CREATED,
        ENTITY_MATCHED,
        ENTITY_UPDATED,
        ALIAS_ADDED,
        SOURCE_LINKED,
        CLAIM_CREATED,
        CLAIM_REINFORCED,
        CLAIM_DISPUTED,
        CLAIM_SUPERSEDED,
        CLAIM_RETRACTED,
        CONFLICT_OPENED,
        PROTECTED_OVERRIDE,
        CONSEQUENCE_APPLIED,
        /** Nothing to do (already in the requested state, merged ids became a self-loop, duplicate proposal). */
        SKIPPED
    }
}
