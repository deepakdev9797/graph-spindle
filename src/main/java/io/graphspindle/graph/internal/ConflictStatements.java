package io.graphspindle.graph.internal;

/** Cypher for Conflict nodes. */
public final class ConflictStatements {

    private ConflictStatements() {
    }

    public static final String CREATE = """
            CREATE (x:Conflict {brainId: $brainId, id: $id, kind: $kind, status: 'OPEN', description: $description,
                                createdAt: datetime()})
            WITH x
            OPTIONAL MATCH (i:Ingestion {brainId: $brainId, id: $ingestionId})
            FOREACH (_ IN CASE WHEN i IS NULL THEN [] ELSE [1] END | CREATE (x)-[:RAISED_BY {brainId: $brainId}]->(i))
            RETURN x.id AS id
            """;

    public static final String INVOLVE_CLAIM = """
            MATCH (x:Conflict {brainId: $brainId, id: $conflictId}), (c:Claim {brainId: $brainId, id: $targetId})
            CREATE (x)-[:INVOLVES {brainId: $brainId, role: $role}]->(c)
            RETURN x.id AS id
            """;

    public static final String INVOLVE_ENTITY = """
            MATCH (x:Conflict {brainId: $brainId, id: $conflictId}), (n:Entity {brainId: $brainId, id: $targetId})
            CREATE (x)-[:INVOLVES {brainId: $brainId, role: $role}]->(n)
            RETURN x.id AS id
            """;

    public static final String FIND_OPEN_DUPLICATE = """
            MATCH (x:Conflict {brainId: $brainId, kind: 'DUPLICATE_CANDIDATE', status: 'OPEN'})
                  -[:INVOLVES {brainId: $brainId}]->(a:Entity {brainId: $brainId, id: $a})
            MATCH (x)-[:INVOLVES {brainId: $brainId}]->(b:Entity {brainId: $brainId, id: $b})
            RETURN x.id AS id LIMIT 1
            """;

    public static final String FIND_BY_ID = """
            MATCH (x:Conflict {brainId: $brainId, id: $id})
            OPTIONAL MATCH (x)-[r:INVOLVES {brainId: $brainId}]->(t)
            RETURN x, collect({role: r.role, id: t.id, label: CASE WHEN t:Claim THEN 'Claim' ELSE 'Entity' END}) AS involves
            """;

    public static final String LIST_OPEN = """
            MATCH (x:Conflict {brainId: $brainId, status: 'OPEN'})
            RETURN x.id AS id ORDER BY x.createdAt, x.id
            """;
}
