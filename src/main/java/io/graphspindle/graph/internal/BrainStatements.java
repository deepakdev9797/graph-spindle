package io.graphspindle.graph.internal;

/** Cypher for Brain nodes and the self entity. */
public final class BrainStatements {

    private BrainStatements() {
    }

    public static final String CREATE = """
            CREATE (b:Brain {id: $brainId, brainId: $brainId, name: $name, description: $description,
                    configJson: $configJson, configVersion: 1, selfEntityId: $selfId, status: 'ACTIVE',
                    createdAt: datetime(), updatedAt: datetime()})
            CREATE (s:Entity:Person {brainId: $brainId, id: $selfId, type: 'Person', name: $selfName,
                    normalizedName: '@self', canonicalKey: 'person:@self', status: 'ACTIVE', origin: 'SYSTEM',
                    descriptionLocked: false, mentionCount: 0, createdAt: datetime(), updatedAt: datetime()})
            RETURN b
            """;

    public static final String FIND = "MATCH (b:Brain {id: $brainId}) RETURN b";

    /** Allowlisted unscoped statement (CypherScopingTest). */
    public static final String LIST_ALL = "MATCH (b:Brain) RETURN b ORDER BY b.id";

    /** Takes the write lock on the brain node (no-op write) and returns it, for optimistic checks. */
    public static final String LOCK = """
            MATCH (b:Brain {id: $brainId})
            SET b._lock = true REMOVE b._lock
            RETURN b
            """;

    public static final String UPDATE = """
            MATCH (b:Brain {id: $brainId})
            SET b.name = coalesce($name, b.name),
                b.description = CASE WHEN $setDescription THEN $description ELSE b.description END,
                b.configJson = coalesce($configJson, b.configJson),
                b.configVersion = b.configVersion + 1,
                b.updatedAt = datetime()
            RETURN b
            """;

    public static final String MARK_DELETING = """
            MATCH (b:Brain {id: $brainId})
            SET b.status = 'DELETING', b.updatedAt = datetime()
            RETURN b
            """;

    /** Batched detach-delete of every node of the brain (the Brain node included). Auto-commit only. */
    public static final String DELETE_ALL = """
            MATCH (n) WHERE n.brainId = $brainId
            CALL (n) { DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS
            """;
}
