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
        SUMMARY_ADJUSTED,
        /** A create_entity carried an attribute-named field (e.g. category); the field was dropped (ADR-028). */
        ATTRIBUTE_FIELD_DROPPED,
        /**
         * A supersede_claim without correction language in the input became a flagged contradiction
         * (DISPUTED claim + CONTRADICTION conflict): the engine, not the model, decides what is an explicit
         * correction (ADR-028).
         */
        CORRECTION_DOWNGRADED,
        /** The named speaker did not exist in the brain; the engine creates it (handle P0) with this plan. */
        SPEAKER_CREATED,
        /**
         * A retract_claim without correction language in the input was not applied; the claim stays
         * ACTIVE (ADR-030).
         */
        RETRACTION_DROPPED,
        /**
         * A new entity's name is very similar to an existing candidate of a compatible type and the model
         * did not propose a merge: the engine opens a DUPLICATE_CANDIDATE conflict (SCHEMA §10.5, ADR-030).
         */
        DUPLICATE_SUSPECTED
    }
}
