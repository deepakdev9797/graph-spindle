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
                    descriptionLocked: false, mentionCount: 0, createdAt: datetime(), updatedAt: datetime(),
                    ftScope: replace($brainId, '-', '_')})
            RETURN b
            """;

    public static final String FIND = "MATCH (b:Brain {id: $brainId}) RETURN b";

    /** Allowlisted unscoped statement (CypherScopingTest). */
    public static final String LIST_ALL = "MATCH (b:Brain) RETURN b ORDER BY b.id";

    /**
     * Takes the write lock on the brain node (no-op write) and returns it, for optimistic checks.
     * Every write path takes this lock first (apply, manual knowledge writes, config updates), so
     * {@link #MARK_DELETING} serializes with in-flight writes and the returned status is current.
     */
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

    /**
     * Batched detach-delete of every node of the brain except the Brain node itself, which is deleted
     * last by {@link #DELETE_BRAIN_IF_EMPTY}. Auto-commit only.
     */
    public static final String DELETE_ALL = """
            MATCH (n) WHERE n.brainId = $brainId AND NOT n:Brain
            CALL (n) { DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS
            """;

    /** Nodes of the brain other than the Brain node (re-check after {@link #DELETE_ALL}). */
    public static final String COUNT_REMAINING = """
            MATCH (n) WHERE n.brainId = $brainId AND NOT n:Brain
            RETURN count(n) AS n
            """;

    /** Deletes the (locked, DELETING) Brain node only when nothing else of the brain is left. */
    public static final String DELETE_BRAIN_IF_EMPTY = """
            MATCH (b:Brain {id: $brainId, status: 'DELETING'})
            SET b._lock = true REMOVE b._lock
            WITH b
            OPTIONAL MATCH (n) WHERE n.brainId = $brainId AND NOT n:Brain
            WITH b, count(n) AS remaining
            WHERE remaining = 0
            DETACH DELETE b
            RETURN remaining
            """;

    /** ACTIVE entities per type, for the vocabulary-removal guard (SCHEMA §11). */
    public static final String TYPES_IN_USE = """
            MATCH (n:Entity {brainId: $brainId, status: 'ACTIVE'})
            WHERE n.type IN $types
            RETURN n.type AS name, count(n) AS n
            """;

    /** ACTIVE or DISPUTED claims per predicate, for the vocabulary-removal guard (SCHEMA §11). */
    public static final String PREDICATES_IN_USE = """
            MATCH (c:Claim {brainId: $brainId})
            WHERE c.predicate IN $predicates AND c.status IN ['ACTIVE', 'DISPUTED']
            RETURN c.predicate AS name, count(c) AS n
            """;

    /**
     * ACTIVE or DISPUTED attribute claims per (subject type, predicate) pair, for the guard against
     * removing or narrowing a property of one type while it is in use (ADR-025).
     */
    public static final String ATTRIBUTES_ON_TYPES_IN_USE = """
            UNWIND $pairs AS p
            MATCH (c:Claim {brainId: $brainId, kind: 'ATTRIBUTE', predicate: p.predicate})
                  -[:SUBJECT]->(e:Entity {brainId: $brainId, type: p.type})
            WHERE c.status IN ['ACTIVE', 'DISPUTED']
            RETURN p.type + '.' + p.predicate AS name, count(c) AS n
            """;
}
