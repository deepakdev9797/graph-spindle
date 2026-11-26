package io.graphspindle.graph.internal;

/** Read-only statements for ProjectionVerifier. */
public final class VerifierStatements {

    private VerifierStatements() {
    }

    /** Active positive relation claims that do not have exactly one matching projection edge. */
    public static final String MISSING_EDGES = """
            MATCH (c:Claim {brainId: $brainId, kind: 'RELATION', status: 'ACTIVE', polarity: 'POSITIVE'})
            OPTIONAL MATCH (s:Entity {brainId: $brainId, id: c.subjectId})-[r {brainId: $brainId, claimId: c.id}]->(o:Entity {brainId: $brainId, id: c.objectId})
            WITH c, [x IN collect(r) WHERE x IS NOT NULL AND type(x) = c.predicate] AS edges
            WHERE size(edges) <> 1
            RETURN c.id AS claimId, c.subjectId AS subjectId, size(edges) AS n
            """;

    /** Projection edges without an active positive claim of the same predicate and endpoints. */
    public static final String ORPHAN_EDGES = """
            MATCH (s:Entity {brainId: $brainId})-[r]->(o)
            WHERE r.claimId IS NOT NULL
            OPTIONAL MATCH (c:Claim {brainId: $brainId, id: r.claimId})
            WITH s, r, o, c
            WHERE c IS NULL OR c.status <> 'ACTIVE' OR c.polarity <> 'POSITIVE' OR c.predicate <> type(r)
               OR c.subjectId <> s.id OR c.objectId <> o.id OR r.brainId <> $brainId OR o.brainId <> $brainId
            RETURN r.claimId AS claimId, s.id AS subjectId, type(r) AS type
            """;

    public static final String ACTIVE_ATTRIBUTE_CLAIMS = """
            MATCH (c:Claim {brainId: $brainId, kind: 'ATTRIBUTE', status: 'ACTIVE', polarity: 'POSITIVE'})
            RETURN c.subjectId AS subjectId, c.predicate AS predicate, c.value AS value, c.cardinality AS cardinality
            """;

    public static final String ENTITY_ATTRIBUTES = """
            MATCH (n:Entity {brainId: $brainId})
            RETURN n.id AS id, [k IN keys(n) WHERE k STARTS WITH 'attr_' | [k, n[k]]] AS attrs
            """;

    /** Claims holding a key while not ACTIVE, or an ACTIVE claim without an activeKey. */
    public static final String KEY_DRIFT = """
            MATCH (c:Claim {brainId: $brainId})
            WHERE (c.status <> 'ACTIVE' AND (c.activeKey IS NOT NULL OR c.slotKey IS NOT NULL))
               OR (c.status = 'ACTIVE' AND c.activeKey IS NULL)
            RETURN c.id AS claimId, c.subjectId AS subjectId
            """;
}
