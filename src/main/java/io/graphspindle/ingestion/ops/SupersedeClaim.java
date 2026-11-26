package io.graphspindle.ingestion.ops;

import java.util.Objects;

/** {@code supersede_claim}: replace an existing claim (by claim handle) with a new one for the same slot. */
public record SupersedeClaim(String claimHandle, boolean explicitCorrection, String reason, AssertClaim replacement)
        implements MutationOperation {

    public static final String OP = "supersede_claim";

    public SupersedeClaim {
        Objects.requireNonNull(claimHandle, "claimHandle");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(replacement, "replacement");
    }

    @Override
    public String wireName() {
        return OP;
    }
}
