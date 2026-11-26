package io.graphspindle.ingestion.ops;

import java.util.List;
import java.util.Objects;

/** {@code create_entity}: a new entity, referable by {@code ref} later in the same proposal. */
public record CreateEntity(String ref, String type, String name, String description, List<String> aliases)
        implements MutationOperation {

    public static final String OP = "create_entity";

    public CreateEntity {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    @Override
    public String wireName() {
        return OP;
    }
}
