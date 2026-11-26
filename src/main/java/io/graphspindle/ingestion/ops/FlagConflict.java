package io.graphspindle.ingestion.ops;

import java.util.Objects;

/** {@code flag_conflict}: the incoming claim contradicts an existing one; stored as DISPUTED. */
public record FlagConflict(String claimHandle, AssertClaim incoming, String description) implements MutationOperation {

    public static final String OP = "flag_conflict";

    public FlagConflict {
        Objects.requireNonNull(claimHandle, "claimHandle");
        Objects.requireNonNull(incoming, "incoming");
        Objects.requireNonNull(description, "description");
    }

    @Override
    public String wireName() {
        return OP;
    }
}
