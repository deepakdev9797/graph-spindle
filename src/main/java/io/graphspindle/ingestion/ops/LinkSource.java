package io.graphspindle.ingestion.ops;

import java.util.Objects;

/** {@code link_source}: request source {@code sourceIndex} describes the target entity. */
public record LinkSource(int sourceIndex, EntityTarget target) implements MutationOperation {

    public static final String OP = "link_source";

    public LinkSource {
        Objects.requireNonNull(target, "target");
    }

    @Override
    public String wireName() {
        return OP;
    }
}
