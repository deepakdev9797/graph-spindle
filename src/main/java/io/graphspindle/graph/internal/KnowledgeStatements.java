package io.graphspindle.graph.internal;

/**
 * Cypher for manual knowledge writes that are not plan commands (M5): entity edits, archiving,
 * the full entity merge (SCHEMA §9.5) and conflict resolution (SCHEMA §9.4).
 */
public final class KnowledgeStatements {

    private KnowledgeStatements() {
    }

    /** Write lock on one entity (no-op write) and its current state. */
    public static final String LOCK_ENTITY = """
            MATCH (n:Entity {brainId: $brainId, id: $id})
            SET n._lock = true REMOVE n._lock
            RETURN n
            """;

    /** Name, key, description and lock changes of a manual entity edit (null keeps the value). */
    public static final String UPDATE_ENTITY = """
            MATCH (n:Entity {brainId: $brainId, id: $id})
            SET n.name = coalesce($name, n.name), n.normalizedName = coalesce($normalizedName, n.normalizedName),
                n.canonicalKey = coalesce($canonicalKey, n.canonicalKey),
                n.description = CASE WHEN $setDescription THEN $description ELSE n.description END,
                n.descriptionLocked = coalesce($locked, n.descriptionLocked), n.updatedAt = datetime()
            RETURN n
            """;

    public static final String ARCHIVE_ENTITY = """
            MATCH (n:Entity {brainId: $brainId, id: $id, status: 'ACTIVE'})
            SET n.status = 'ARCHIVED', n.archivedAt = datetime(), n.archivedBy = $by, n.updatedAt = datetime()
            REMOVE n.ftScope
            RETURN n
            """;

    /** Every claim (any status) whose subject or object is the entity. */
    public static final String CLAIMS_TOUCHING = """
            MATCH (c:Claim {brainId: $brainId})
            WHERE c.subjectId = $id OR c.objectId = $id
            RETURN c ORDER BY c.id
            """;

    /** Removes the keys of an ACTIVE claim before it is re-keyed (status unchanged). */
    public static final String CLEAR_KEYS = """
            MATCH (c:Claim {brainId: $brainId, id: $id})
            REMOVE c.activeKey, c.slotKey
            RETURN c
            """;

    public static final String REPOINT_SUBJECT = """
            MATCH (c:Claim {brainId: $brainId, id: $claimId})-[r:SUBJECT {brainId: $brainId}]->(:Entity {brainId: $brainId, id: $fromId})
            MATCH (s:Entity {brainId: $brainId, id: $toId})
            DELETE r
            CREATE (c)-[:SUBJECT {brainId: $brainId}]->(s)
            SET c.subjectId = s.id
            RETURN c
            """;

    public static final String REPOINT_OBJECT = """
            MATCH (c:Claim {brainId: $brainId, id: $claimId})-[r:OBJECT {brainId: $brainId}]->(:Entity {brainId: $brainId, id: $fromId})
            MATCH (o:Entity {brainId: $brainId, id: $toId})
            DELETE r
            CREATE (c)-[:OBJECT {brainId: $brainId}]->(o)
            SET c.objectId = o.id
            RETURN c
            """;

    /** Makes a claim ACTIVE with freshly computed keys (searchable again). */
    public static final String ACTIVATE = """
            MATCH (c:Claim {brainId: $brainId, id: $id})
            SET c.status = 'ACTIVE', c.activeKey = $activeKey, c.slotKey = $slotKey,
                c.ftScope = replace($brainId, '-', '_'), c.statusChangedAt = datetime(), c.statusReason = $reason
            RETURN c
            """;

    /** Copies EVIDENCED_BY edges (once per ingestion) from one claim to another; the source keeps them. */
    public static final String COPY_EVIDENCE = """
            MATCH (f:Claim {brainId: $brainId, id: $fromId})-[e:EVIDENCED_BY {brainId: $brainId}]->(i:Ingestion {brainId: $brainId})
            MATCH (t:Claim {brainId: $brainId, id: $toId})
            MERGE (t)-[e2:EVIDENCED_BY {brainId: $brainId}]->(i)
            ON CREATE SET e2.quote = e.quote, e2.confidence = e.confidence, e2.recordedAt = e.recordedAt
            RETURN count(e2) AS n
            """;

    public static final String COPY_CITES = """
            MATCH (f:Claim {brainId: $brainId, id: $fromId})-[:CITES {brainId: $brainId}]->(s:SourceRef {brainId: $brainId})
            MATCH (t:Claim {brainId: $brainId, id: $toId})
            MERGE (t)-[:CITES {brainId: $brainId}]->(s)
            RETURN count(s) AS n
            """;

    /** After evidence was copied in: recount, keep the max confidence, and keep MANUAL protection. */
    public static final String ABSORB = """
            MATCH (c:Claim {brainId: $brainId, id: $id})
            SET c.evidenceCount = COUNT { (c)-[:EVIDENCED_BY {brainId: $brainId}]->(:Ingestion {brainId: $brainId}) },
                c.confidence = CASE WHEN $confidence > c.confidence THEN $confidence ELSE c.confidence END,
                c.origin = CASE WHEN $manual THEN 'MANUAL' ELSE c.origin END
            RETURN c
            """;

    public static final String SET_VALIDITY = """
            MATCH (c:Claim {brainId: $brainId, id: $id})
            SET c.validFrom = $validFrom, c.validFromSort = $validFromSort, c.validTo = $validTo,
                c.validToSort = $validToSort
            RETURN c
            """;

    public static final String MOVE_ALIASES = """
            MATCH (l:Entity {brainId: $brainId, id: $loserId})-[h:HAS_ALIAS {brainId: $brainId}]->(a:Alias {brainId: $brainId})
            MATCH (s:Entity {brainId: $brainId, id: $survivorId})
            MERGE (s)-[h2:HAS_ALIAS {brainId: $brainId}]->(a)
            ON CREATE SET h2.origin = h.origin, h2.createdAt = h.createdAt
            DELETE h
            RETURN count(a) AS n
            """;

    public static final String MOVE_MENTIONS = """
            MATCH (l:Entity {brainId: $brainId, id: $loserId})-[m:MENTIONED_IN {brainId: $brainId}]->(i:Ingestion {brainId: $brainId})
            MATCH (s:Entity {brainId: $brainId, id: $survivorId})
            MERGE (s)-[m2:MENTIONED_IN {brainId: $brainId}]->(i)
            ON CREATE SET m2.surfaceForm = m.surfaceForm
            DELETE m
            RETURN count(i) AS n
            """;

    public static final String RECOUNT_MENTIONS = """
            MATCH (n:Entity {brainId: $brainId, id: $id})
            SET n.mentionCount = COUNT { (n)-[:MENTIONED_IN {brainId: $brainId}]->(:Ingestion {brainId: $brainId}) }
            RETURN n.mentionCount AS n
            """;

    public static final String MOVE_DESCRIBES = """
            MATCH (src:SourceRef {brainId: $brainId})-[d:DESCRIBES {brainId: $brainId}]->(l:Entity {brainId: $brainId, id: $loserId})
            MATCH (s:Entity {brainId: $brainId, id: $survivorId})
            MERGE (src)-[d2:DESCRIBES {brainId: $brainId}]->(s)
            ON CREATE SET d2.claimless = true
            DELETE d
            RETURN count(src) AS n
            """;

    /** Takes over the loser's description when the survivor has none. */
    public static final String INHERIT_DESCRIPTION = """
            MATCH (l:Entity {brainId: $brainId, id: $loserId}), (s:Entity {brainId: $brainId, id: $survivorId})
            WHERE s.description IS NULL AND l.description IS NOT NULL
            SET s.description = l.description, s.updatedAt = datetime()
            RETURN s.id AS id
            """;

    /** OPEN duplicate-candidate conflicts between the two entities become RESOLVED with MERGE. */
    public static final String RESOLVE_DUPLICATES_BETWEEN = """
            MATCH (x:Conflict {brainId: $brainId, kind: 'DUPLICATE_CANDIDATE', status: 'OPEN'})
                  -[:INVOLVES {brainId: $brainId}]->(:Entity {brainId: $brainId, id: $a})
            MATCH (x)-[:INVOLVES {brainId: $brainId}]->(:Entity {brainId: $brainId, id: $b})
            SET x.status = 'RESOLVED', x.resolution = 'MERGE', x.resolvedAt = datetime(), x.resolvedBy = $by,
                x.resolutionNote = $note
            RETURN x.id AS id
            """;

    /** OPEN conflicts that involve the loser entity now involve the survivor (closed ones keep their history). */
    public static final String REPOINT_INVOLVES = """
            MATCH (x:Conflict {brainId: $brainId, status: 'OPEN'})-[r:INVOLVES {brainId: $brainId}]->(l:Entity {brainId: $brainId, id: $loserId})
            MATCH (s:Entity {brainId: $brainId, id: $survivorId})
            CREATE (x)-[:INVOLVES {brainId: $brainId, role: r.role}]->(s)
            DELETE r
            RETURN count(x) AS n
            """;

    public static final String RESOLVE_CONFLICT = """
            MATCH (x:Conflict {brainId: $brainId, id: $id, status: 'OPEN'})
            SET x.status = $status, x.resolution = $resolution, x.resolvedAt = datetime(), x.resolvedBy = $by,
                x.resolutionNote = $note
            RETURN x.id AS id
            """;

    /** Write lock on one conflict (no-op write), so two resolutions of it serialize. */
    public static final String LOCK_CONFLICT = """
            MATCH (x:Conflict {brainId: $brainId, id: $id})
            SET x._lock = true REMOVE x._lock
            RETURN x.status AS status
            """;
}
