package io.graphspindle.ingestion.ops;

import java.util.Objects;

/** {@code update_entity}: replace the description of an entity. */
public record UpdateEntity(EntityTarget target, String description, String reason) implements MutationOperation {

    public static final String OP = "update_entity";

    public UpdateEntity {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(description, "description");
    }

    @Override
    public String wireName() {
        return OP;
    }
}
