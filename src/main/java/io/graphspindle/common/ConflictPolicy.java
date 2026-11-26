package io.graphspindle.common;

/** Per-brain conflict policy (SCHEMA §9.4). In {@code common} because brain config and graph apply both use it. */
public enum ConflictPolicy {
    /** Default: explicit, evidenced corrections supersede LLM claims; implicit contradictions are flagged. */
    EXPLICIT_CORRECTIONS_SUPERSEDE,
    /** Every contradiction, even an explicit correction, is flagged and not applied. */
    ALWAYS_FLAG,
    /** Any contradiction from the same principal supersedes (never MANUAL claims). */
    LATEST_WINS
}
