package io.graphspindle.graph.internal;

/**
 * Read-only Cypher for manual knowledge management and retrieval (M5). Every node and relationship
 * in every pattern is matched with {@code brainId}. Keyset pagination is by id (UUIDv7, time-ordered).
 */
public final class RetrievalStatements {

    private RetrievalStatements() {
    }

    public static final String LIST_ENTITIES = """
            MATCH (n:Entity {brainId: $brainId})
            WHERE ($type IS NULL OR n.type = $type) AND ($status IS NULL OR n.status = $status)
              AND ($after IS NULL OR n.id > $after)
            RETURN n ORDER BY n.id LIMIT $limit
            """;

    public static final String ENTITIES_BY_IDS = """
            MATCH (n:Entity {brainId: $brainId})
            WHERE n.id IN $ids
            RETURN n
            """;

    public static final String ALIASES_OF = """
            MATCH (n:Entity {brainId: $brainId, id: $id})-[h:HAS_ALIAS {brainId: $brainId}]->(a:Alias {brainId: $brainId})
            RETURN a.normalized AS normalized, a.display AS display, h.origin AS origin ORDER BY normalized
            """;

    public static final String LIST_CLAIMS = """
            MATCH (c:Claim {brainId: $brainId})
            WHERE ($subjectId IS NULL OR c.subjectId = $subjectId) AND ($objectId IS NULL OR c.objectId = $objectId)
              AND ($predicate IS NULL OR c.predicate = $predicate) AND ($status IS NULL OR c.status = $status)
              AND ($after IS NULL OR c.id > $after)
            RETURN c ORDER BY c.id LIMIT $limit
            """;

    /** Claims where the entity is subject or object, newest first. */
    public static final String CLAIMS_OF_ENTITY = """
            MATCH (c:Claim {brainId: $brainId})
            WHERE (c.subjectId = $id OR c.objectId = $id) AND c.status IN $statuses
            RETURN c ORDER BY c.recordedAt DESC, c.id DESC LIMIT $limit
            """;

    /** Relation claims with the given statuses where the entity is subject or object, newest first. */
    public static final String RELATION_CLAIMS_OF_ENTITY = """
            MATCH (c:Claim {brainId: $brainId})
            WHERE (c.subjectId = $id OR c.objectId = $id) AND c.status IN $statuses AND c.kind = 'RELATION'
            RETURN c ORDER BY c.recordedAt DESC, c.id DESC LIMIT $limit
            """;

    /**
     * The newest {@code $limit} claims (any status) recorded at or before {@code $asOf} (null: no
     * bound) where the entity is, or was before a merge re-pointed it, subject or object; with their
     * SUPERSEDES targets and the earliest {@code recordedAt} of a superseding claim. Newest first: the
     * record-time timeline for point-in-time reads and history (ADR-026), reversed by the reader.
     */
    public static final String CLAIM_TIMELINE_OF_ENTITY = """
            MATCH (c:Claim {brainId: $brainId})
            WHERE (c.subjectId = $id OR c.objectId = $id OR c.originalSubjectId = $id OR c.originalObjectId = $id)
              AND ($asOf IS NULL OR c.recordedAt <= $asOf)
            WITH c ORDER BY c.recordedAt DESC, c.id DESC LIMIT $limit
            OPTIONAL MATCH (c)-[s:SUPERSEDES {brainId: $brainId}]->(o:Claim {brainId: $brainId})
            WITH c, collect(o.id) AS supersedes, head(collect(s.reason)) AS reason
            OPTIONAL MATCH (n:Claim {brainId: $brainId})-[:SUPERSEDES {brainId: $brainId}]->(c)
            WITH c, supersedes, reason, min(n.recordedAt) AS supersededAt
            RETURN c, supersedes, reason, supersededAt ORDER BY c.recordedAt DESC, c.id DESC
            """;

    /** Creation time of an entity and, when it was merged, the merge instant. */
    public static final String ENTITY_CREATED_AT = """
            MATCH (n:Entity {brainId: $brainId, id: $id})
            OPTIONAL MATCH (n)-[m:MERGED_INTO {brainId: $brainId}]->(:Entity {brainId: $brainId})
            RETURN n.createdAt AS createdAt, m.at AS mergedAt
            """;

    /** The connected SUPERSEDES chain of a claim (both directions), oldest first. */
    public static final String CLAIM_HISTORY = """
            MATCH (c:Claim {brainId: $brainId, id: $id})
            OPTIONAL MATCH p = (c)-[:SUPERSEDES*1..50]-(x:Claim {brainId: $brainId})
            WHERE all(r IN relationships(p) WHERE r.brainId = $brainId)
            WITH c, collect(DISTINCT x) AS xs
            UNWIND [c] + xs AS h
            WITH DISTINCT h
            RETURN h AS c ORDER BY h.recordedAt, h.id
            """;

    public static final String CLAIM_LINKS = """
            MATCH (c:Claim {brainId: $brainId, id: $id})
            OPTIONAL MATCH (c)-[s:SUPERSEDES {brainId: $brainId}]->(o:Claim {brainId: $brainId})
            WITH c, collect(o.id) AS supersedes, head(collect(s.reason)) AS reason
            OPTIONAL MATCH (n:Claim {brainId: $brainId})-[:SUPERSEDES {brainId: $brainId}]->(c)
            RETURN supersedes, collect(n.id) AS supersededBy, reason
            """;

    public static final String EVIDENCE = """
            MATCH (c:Claim {brainId: $brainId, id: $id})-[e:EVIDENCED_BY {brainId: $brainId}]->(i:Ingestion {brainId: $brainId})
            RETURN i.id AS ingestionId, e.quote AS quote, e.confidence AS confidence, e.recordedAt AS recordedAt,
                   i.principal AS principal
            ORDER BY e.recordedAt, i.id
            """;

    public static final String CITED_SOURCES = """
            MATCH (c:Claim {brainId: $brainId, id: $id})-[:CITES {brainId: $brainId}]->(s:SourceRef {brainId: $brainId})
            RETURN s ORDER BY s.id LIMIT $limit
            """;

    public static final String CONFLICTS_OF_CLAIM = """
            MATCH (x:Conflict {brainId: $brainId})-[:INVOLVES {brainId: $brainId}]->(c:Claim {brainId: $brainId, id: $id})
            WHERE $status IS NULL OR x.status = $status
            RETURN DISTINCT x.id AS id ORDER BY id
            """;

    /** OPEN conflicts involving the entity itself or one of its claims (as subject or object). */
    public static final String OPEN_CONFLICTS_OF_ENTITY = """
            MATCH (x:Conflict {brainId: $brainId, status: 'OPEN'})-[:INVOLVES {brainId: $brainId}]->(t {brainId: $brainId})
            WHERE (t:Entity AND t.id = $id) OR (t:Claim AND (t.subjectId = $id OR t.objectId = $id))
            RETURN DISTINCT x.id AS id ORDER BY id
            """;

    public static final String LIST_CONFLICTS = """
            MATCH (x:Conflict {brainId: $brainId})
            WHERE ($status IS NULL OR x.status = $status) AND ($kind IS NULL OR x.kind = $kind)
              AND ($after IS NULL OR x.id > $after)
            WITH x ORDER BY x.id LIMIT $limit
            OPTIONAL MATCH (x)-[r:INVOLVES {brainId: $brainId}]->(t {brainId: $brainId})
            WITH x, collect({role: r.role, id: t.id, label: CASE WHEN t:Claim THEN 'Claim' ELSE 'Entity' END}) AS involves
            OPTIONAL MATCH (x)-[:RAISED_BY {brainId: $brainId}]->(i:Ingestion {brainId: $brainId})
            RETURN x, involves, head(collect(i.id)) AS raisedBy ORDER BY x.id
            """;

    public static final String CONFLICT = """
            MATCH (x:Conflict {brainId: $brainId, id: $id})
            OPTIONAL MATCH (x)-[r:INVOLVES {brainId: $brainId}]->(t {brainId: $brainId})
            WITH x, collect({role: r.role, id: t.id, label: CASE WHEN t:Claim THEN 'Claim' ELSE 'Entity' END}) AS involves
            OPTIONAL MATCH (x)-[:RAISED_BY {brainId: $brainId}]->(i:Ingestion {brainId: $brainId})
            RETURN x, involves, head(collect(i.id)) AS raisedBy
            """;

    public static final String LIST_SOURCES = """
            MATCH (s:SourceRef {brainId: $brainId})
            WHERE $after IS NULL OR s.id > $after
            RETURN s ORDER BY s.id LIMIT $limit
            """;

    public static final String SOURCE = "MATCH (s:SourceRef {brainId: $brainId, id: $id}) RETURN s";

    public static final String SOURCE_DESCRIBES = """
            MATCH (s:SourceRef {brainId: $brainId, id: $id})-[:DESCRIBES {brainId: $brainId}]->(n:Entity {brainId: $brainId})
            RETURN n.id AS id ORDER BY id LIMIT $limit
            """;

    public static final String SOURCE_CITED_BY = """
            MATCH (c:Claim {brainId: $brainId})-[:CITES {brainId: $brainId}]->(s:SourceRef {brainId: $brainId, id: $id})
            RETURN c.id AS id ORDER BY id LIMIT $limit
            """;

    public static final String SOURCES_DESCRIBING = """
            MATCH (s:SourceRef {brainId: $brainId})-[:DESCRIBES {brainId: $brainId}]->(n:Entity {brainId: $brainId, id: $id})
            RETURN s ORDER BY s.id LIMIT $limit
            """;

    /** Fulltext over ACTIVE claim statements; {@code $query} is built by {@link Lucene#scoped}. */
    public static final String FULLTEXT_CLAIMS = """
            CALL db.index.fulltext.queryNodes('claim_text', $query, {limit: $oversample}) YIELD node, score
            WHERE node.brainId = $brainId AND node.status = 'ACTIVE'
            RETURN node AS c, score ORDER BY score DESC LIMIT $limit
            """;

    /** Fulltext over source titles and summaries, with the entities each source relates to. */
    public static final String FULLTEXT_SOURCES = """
            CALL db.index.fulltext.queryNodes('source_text', $query, {limit: $oversample}) YIELD node, score
            WHERE node.brainId = $brainId
            WITH node, score ORDER BY score DESC LIMIT $limit
            OPTIONAL MATCH (node)-[:DESCRIBES {brainId: $brainId}]->(n:Entity {brainId: $brainId})
            WITH node, score, collect(DISTINCT n.id) AS described
            OPTIONAL MATCH (c:Claim {brainId: $brainId, status: 'ACTIVE'})-[:CITES {brainId: $brainId}]->(node)
            WITH node, score, described, collect(DISTINCT c.subjectId) AS citing
            RETURN node AS s, score, described + citing AS entityIds
            """;

    /**
     * Neighbours within one hop over projection edges only: relationships that carry a
     * {@code claimId}, whose type is one of the vocabulary predicates {@code $types}, between ACTIVE
     * entities of this brain.
     */
    public static final String NEIGHBORS_1 = """
            MATCH (s:Entity {brainId: $brainId, id: $id})-[r]-(n:Entity {brainId: $brainId})
            WHERE n <> s AND n.status = 'ACTIVE' AND r.brainId = $brainId AND r.claimId IS NOT NULL
              AND type(r) IN $types
            WITH n, 1 AS distance
            RETURN DISTINCT n, distance ORDER BY distance, n.name, n.id LIMIT $limit
            """;

    /** As {@link #NEIGHBORS_1}, up to two hops (the intermediate entity must be ACTIVE too). */
    public static final String NEIGHBORS_2 = """
            MATCH p = (s:Entity {brainId: $brainId, id: $id})-[*1..2]-(n:Entity {brainId: $brainId})
            WHERE n <> s AND n.status = 'ACTIVE'
              AND all(r IN relationships(p) WHERE r.brainId = $brainId AND r.claimId IS NOT NULL AND type(r) IN $types)
              AND all(x IN nodes(p) WHERE x:Entity AND x.brainId = $brainId AND (x = s OR x.status = 'ACTIVE'))
            WITH n, min(length(p)) AS distance
            RETURN n, distance ORDER BY distance, n.name, n.id LIMIT $limit
            """;

    /** Projection edges among a set of entities of this brain. */
    public static final String EDGES_AMONG = """
            MATCH (a:Entity {brainId: $brainId})-[r]->(b:Entity {brainId: $brainId})
            WHERE a.id IN $ids AND b.id IN $ids AND r.brainId = $brainId AND r.claimId IS NOT NULL
              AND type(r) IN $types
            RETURN r.claimId AS claimId, type(r) AS predicate, a.id AS fromId, b.id AS toId, r.confidence AS confidence
            ORDER BY claimId LIMIT $limit
            """;
}
