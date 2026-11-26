package io.graphspindle.ingestion.ops;

import java.util.Objects;

/** {@code add_alias}: another name for an entity. */
public record AddAlias(EntityTarget target, String alias) implements MutationOperation {

    public static final String OP = "add_alias";

    public AddAlias {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(alias, "alias");
    }

    @Override
    public String wireName() {
        return OP;
    }
}
