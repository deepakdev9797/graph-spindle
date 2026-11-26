package io.graphspindle.graph.internal;

/** Cypher for Claim nodes (SCHEMA §9.2). {@code $props} never contains brainId (ScopedTx refuses it at top level). */
public final class ClaimStatements {

    private ClaimStatements() {
    }

    public static final String FIND_BY_ID = "MATCH (c:Claim {brainId: $brainId, id: $id}) RETURN c";

    public static final String FIND_BY_ACTIVE_KEY = "MATCH (c:Claim {brainId: $brainId, activeKey: $activeKey}) RETURN c";

    public static final String FIND_BY_SLOT_KEY = "MATCH (c:Claim {brainId: $brainId, slotKey: $slotKey}) RETURN c";

    public static final String OF_SUBJECT = """
            MATCH (c:Claim {brainId: $brainId, subjectId: $subjectId})
            RETURN c ORDER BY c.recordedAt, c.id
            """;

    public static final String CREATE_RELATION = """
            MATCH (s:Entity {brainId: $brainId, id: $subjectId}), (o:Entity {brainId: $brainId, id: $objectId})
            CREATE (c:Claim)
            SET c = $props, c.brainId = $brainId, c.recordedAt = datetime(), c.statusChangedAt = datetime(),
                c.ftScope = CASE WHEN $props.status = 'ACTIVE' THEN replace($brainId, '-', '_') ELSE null END
            CREATE (c)-[:SUBJECT {brainId: $brainId}]->(s)
            CREATE (c)-[:OBJECT {brainId: $brainId}]->(o)
            RETURN c
            """;

    public static final String CREATE_ATTRIBUTE = """
            MATCH (s:Entity {brainId: $brainId, id: $subjectId})
            CREATE (c:Claim)
            SET c = $props, c.brainId = $brainId, c.recordedAt = datetime(), c.statusChangedAt = datetime(),
                c.ftScope = CASE WHEN $props.status = 'ACTIVE' THEN replace($brainId, '-', '_') ELSE null END
            CREATE (c)-[:SUBJECT {brainId: $brainId}]->(s)
            RETURN c
            """;

    /** Adds evidence from an ingestion (once per ingestion) and keeps the max confidence. */
    public static final String ADD_EVIDENCE = """
            MATCH (c:Claim {brainId: $brainId, id: $claimId}), (i:Ingestion {brainId: $brainId, id: $ingestionId})
            MERGE (c)-[e:EVIDENCED_BY {brainId: $brainId}]->(i)
            ON CREATE SET e.quote = $quote, e.confidence = $confidence, e.recordedAt = datetime(), e._new = true
            WITH c, e, e._new IS NOT NULL AS isNew
            REMOVE e._new
            SET c.evidenceCount = coalesce(c.evidenceCount, 0) + CASE WHEN isNew THEN 1 ELSE 0 END,
                c.confidence = CASE WHEN $confidence > c.confidence THEN $confidence ELSE c.confidence END
            RETURN c
            """;

    public static final String CONFIRM_MANUAL = """
            MATCH (c:Claim {brainId: $brainId, id: $claimId})
            SET c.origin = 'MANUAL', c.confidence = 1.0, c.confirmedAt = datetime(), c.confirmedBy = $principal
            RETURN c
            """;

    public static final String CITE = """
            MATCH (c:Claim {brainId: $brainId, id: $claimId}), (s:SourceRef {brainId: $brainId, id: $sourceId})
            MERGE (c)-[:CITES {brainId: $brainId}]->(s)
            RETURN c.id AS id
            """;

    /** Any status other than ACTIVE: the keys are removed in the same statement (SCHEMA §7.4). */
    public static final String LEAVE_ACTIVE = """
            MATCH (c:Claim {brainId: $brainId, id: $claimId})
            SET c.status = $status, c.statusChangedAt = datetime(), c.statusReason = $reason,
                c.statusEvidence = $evidence, c.statusChangedByIngestionId = $ingestionId
            REMOVE c.activeKey, c.slotKey, c.ftScope
            RETURN c
            """;

    public static final String SUPERSEDES = """
            MATCH (n:Claim {brainId: $brainId, id: $newId}), (o:Claim {brainId: $brainId, id: $oldId})
            MERGE (n)-[r:SUPERSEDES {brainId: $brainId}]->(o)
            ON CREATE SET r.reason = $reason, r.at = datetime()
            RETURN n.id AS id
            """;

    public static final String SET_VALID_TO = """
            MATCH (c:Claim {brainId: $brainId, id: $claimId})
            SET c.validTo = $validTo, c.validToSort = $validToSort
            RETURN c.id AS id
            """;

    /** Active positive attribute values of one slot, for the attr_* projection. */
    public static final String ACTIVE_ATTRIBUTE_VALUES = """
            MATCH (c:Claim {brainId: $brainId, subjectId: $subjectId, status: 'ACTIVE'})
            WHERE c.predicate = $predicate AND c.kind = 'ATTRIBUTE' AND c.polarity = 'POSITIVE'
            RETURN c.value AS value, c.cardinality AS cardinality ORDER BY value
            """;
}
