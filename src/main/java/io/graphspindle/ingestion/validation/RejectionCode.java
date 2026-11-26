package io.graphspindle.ingestion.validation;

/**
 * Stable rejection codes returned in {@code IngestResult.rejected[]} (ARCHITECTURE §5).
 * {@link #UNKNOWN_FIELD} and {@link #DEPENDENCY_REJECTED} are additions recorded in DECISIONS.md.
 */
public enum RejectionCode {
    UNKNOWN_OP,
    UNKNOWN_FIELD,
    UNKNOWN_TYPE,
    UNKNOWN_PREDICATE,
    TYPE_PAIR_NOT_ALLOWED,
    INVALID_VALUE,
    UNKNOWN_HANDLE,
    DUPLICATE_REF,
    TYPE_INCOMPATIBLE_MATCH,
    EVIDENCE_NOT_FOUND,
    SELF_LOOP,
    LIMIT_EXCEEDED,
    LOW_CONFIDENCE,
    SECRET_DETECTED,
    NAME_INVALID,
    PROTECTED_TARGET,
    NOT_SLOT_CONFLICT,
    DEPENDENCY_REJECTED,
    /** supersede_claim on a Decision's CHOOSES/REJECTS claim: decisions are immutable records (ADR-028). */
    DECISION_IMMUTABLE
}
