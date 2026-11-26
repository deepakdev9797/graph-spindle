package io.graphspindle.graph.internal;

/** Cypher for Alias nodes. */
public final class AliasStatements {

    private AliasStatements() {
    }

    public static final String UPSERT = """
            MATCH (n:Entity {brainId: $brainId, id: $entityId})
            MERGE (a:Alias {brainId: $brainId, normalized: $normalized})
            ON CREATE SET a.display = $display, a.createdAt = datetime()
            MERGE (n)-[h:HAS_ALIAS {brainId: $brainId}]->(a)
            ON CREATE SET h.origin = $origin, h.createdAt = datetime(), h._new = true
            WITH h, h._new IS NOT NULL AS isNew
            REMOVE h._new
            RETURN isNew
            """;

    public static final String LOOKUP = """
            MATCH (a:Alias {brainId: $brainId, normalized: $normalized})<-[:HAS_ALIAS {brainId: $brainId}]-(n:Entity {brainId: $brainId})
            RETURN n ORDER BY n.id
            """;

    public static final String OF_ENTITY = """
            MATCH (n:Entity {brainId: $brainId, id: $entityId})-[:HAS_ALIAS {brainId: $brainId}]->(a:Alias {brainId: $brainId})
            RETURN a.normalized AS normalized ORDER BY normalized
            """;
}
