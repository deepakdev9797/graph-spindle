package io.graphspindle.ingestion.validation;

/** A silent normalization applied during validation, reported as {@code adjusted[]} (ARCHITECTURE §5). */
public record Adjustment(int opIndex, String op, Kind kind, String message) {

    public enum Kind {
        /** create_entity matched an existing (or earlier proposed) entity and became a reference. */
        CREATE_BECAME_REFERENCE,
        /** Confidence clamped to maxLlmConfidence or the predicate cap. */
        CONFIDENCE_CLAMPED,
        /** The claim contradicts an ACTIVE slot claim; the conflict policy decides at apply time. */
        CONTRADICTION_DETECTED,
        /** The claim repeats an ACTIVE claim; it will reinforce it. */
        REINFORCEMENT,
        /** An alias was dropped (empty, duplicate, or already known). */
        ALIAS_DROPPED,
        /** A description on a create that became a reference was not applied. */
        DESCRIPTION_IGNORED,
        /** Summary truncated or redacted. */
        SUMMARY_ADJUSTED
    }
}
