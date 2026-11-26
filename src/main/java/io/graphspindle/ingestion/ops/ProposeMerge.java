package io.graphspindle.ingestion.ops;

import java.util.Objects;

/** {@code propose_merge}: two entities may be duplicates; creates a DUPLICATE_CANDIDATE conflict only. */
public record ProposeMerge(EntityTarget a, EntityTarget b, String reason) implements MutationOperation {

    public static final String OP = "propose_merge";

    public ProposeMerge {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        Objects.requireNonNull(reason, "reason");
    }

    @Override
    public String wireName() {
        return OP;
    }
}
