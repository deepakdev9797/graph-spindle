package io.graphspindle.graph.internal;

import java.util.List;

/** Constraints and indexes (SCHEMA §8). Schema statements are allowlisted in CypherScopingTest. */
public final class SchemaStatements {

    private SchemaStatements() {
    }

    public static final List<String> CONSTRAINTS = List.of(
            "CREATE CONSTRAINT brain_id_unique IF NOT EXISTS FOR (b:Brain) REQUIRE b.id IS UNIQUE",
            "CREATE CONSTRAINT entity_id_unique IF NOT EXISTS FOR (n:Entity) REQUIRE (n.brainId, n.id) IS UNIQUE",
            "CREATE CONSTRAINT entity_key_unique IF NOT EXISTS FOR (n:Entity) REQUIRE (n.brainId, n.canonicalKey) IS UNIQUE");

    public static final List<String> INDEXES = List.of();

    public static final List<String> ALL = java.util.stream.Stream.concat(CONSTRAINTS.stream(), INDEXES.stream())
            .toList();
}
