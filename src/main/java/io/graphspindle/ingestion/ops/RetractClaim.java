package io.graphspindle.ingestion.ops;

import java.util.Objects;

/** {@code retract_claim}: an existing claim is no longer true, with evidence. */
public record RetractClaim(String claimHandle, String reason, String evidence) implements MutationOperation {

    public static final String OP = "retract_claim";

    public RetractClaim {
        Objects.requireNonNull(claimHandle, "claimHandle");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(evidence, "evidence");
    }

    @Override
    public String wireName() {
        return OP;
    }
}
