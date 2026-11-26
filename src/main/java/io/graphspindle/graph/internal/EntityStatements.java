package io.graphspindle.graph.internal;

/** Cypher for Entity nodes (SCHEMA §9.1). Labels come only from the vocabulary whitelist ({@link Tokens}). */
public final class EntityStatements {

    private EntityStatements() {
    }

    /** MERGE by canonicalKey (backed by entity_key_unique). The type label is idempotent: the key embeds the type. */
    public static final String MERGE_BY_KEY = """
            MERGE (n:Entity {brainId: $brainId, canonicalKey: $canonicalKey})
            ON CREATE SET n.id = $id, n.type = $type, n.name = $name, n.normalizedName = $normalizedName,
                          n.description = $description, n.descriptionLocked = false, n.status = 'ACTIVE',
                          n.origin = $origin, n.createdAt = datetime(), n.updatedAt = datetime(), n.mentionCount = 0,
                          n.createdByIngestionId = $ingestionId, n.ftScope = replace($brainId, '-', '_'),
                          n._new = true
            WITH n, n._new IS NOT NULL AS created
            REMOVE n._new
            SET n:$($type)
            SET n.description = CASE WHEN NOT created AND n.description IS NULL AND NOT coalesce(n.descriptionLocked, false)
                                     THEN $description ELSE n.description END
            RETURN n.id AS id, n.type AS type, n.status AS status, created
            """;

    public static final String FIND_BY_ID = "MATCH (n:Entity {brainId: $brainId, id: $id}) RETURN n";

    public static final String FIND_BY_KEY = "MATCH (n:Entity {brainId: $brainId, canonicalKey: $canonicalKey}) RETURN n";

    /** Follows MERGED_INTO (at most 5 hops) to the first non-merged survivor; returns the entity itself otherwise. */
    public static final String RESOLVE = """
            MATCH (n:Entity {brainId: $brainId, id: $id})
            OPTIONAL MATCH p = (n)-[:MERGED_INTO*1..5]->(s:Entity {brainId: $brainId})
            WHERE s.status <> 'MERGED' AND all(r IN relationships(p) WHERE r.brainId = $brainId)
            WITH n, s ORDER BY length(p) ASC
            WITH n, head(collect(s)) AS survivor
            RETURN coalesce(survivor, n) AS n
            """;

    /** Tombstones {@code loser} and links it to {@code survivor} (the re-pointing part of a merge is M5). */
    public static final String MARK_MERGED = """
            MATCH (l:Entity {brainId: $brainId, id: $loserId}), (s:Entity {brainId: $brainId, id: $survivorId})
            SET l.status = 'MERGED', l.mergedIntoId = s.id, l.updatedAt = datetime()
            REMOVE l.ftScope
            MERGE (l)-[r:MERGED_INTO {brainId: $brainId}]->(s)
            ON CREATE SET r.at = datetime.transaction(), r.by = $by
            RETURN l.id AS id
            """;

    public static final String UPDATE_DESCRIPTION = """
            MATCH (n:Entity {brainId: $brainId, id: $id})
            SET n.description = $description, n.descriptionLocked = $locked, n.updatedAt = datetime()
            RETURN n.id AS id
            """;

    /** One MENTIONED_IN per ingestion; mentionCount increases only when the edge is new. */
    public static final String MENTION = """
            MATCH (n:Entity {brainId: $brainId, id: $id}), (i:Ingestion {brainId: $brainId, id: $ingestionId})
            MERGE (n)-[m:MENTIONED_IN {brainId: $brainId}]->(i)
            ON CREATE SET m.surfaceForm = $surfaceForm, m._new = true
            WITH n, m, m._new IS NOT NULL AS isNew
            REMOVE m._new
            SET n.mentionCount = coalesce(n.mentionCount, 0) + CASE WHEN isNew THEN 1 ELSE 0 END,
                n.lastMentionedAt = CASE WHEN isNew THEN datetime() ELSE n.lastMentionedAt END
            RETURN isNew
            """;

    /**
     * Fulltext lookup. The index is shared by all brains, so {@code $query} must be built by
     * {@link Lucene#scoped}: it requires the brain's {@code ftScope} token inside Lucene, so other
     * brains can never starve this brain's results (QA M4 F3). The WHERE clause is defence in depth.
     */
    public static final String FULLTEXT = """
            CALL db.index.fulltext.queryNodes('entity_text', $query, {limit: $oversample}) YIELD node, score
            WHERE node.brainId = $brainId AND node.status = 'ACTIVE'
            RETURN node AS n, score ORDER BY score DESC LIMIT $limit
            """;

    public static final String SET_ATTRIBUTES = """
            MATCH (n:Entity {brainId: $brainId, id: $id})
            SET n += $props
            RETURN n.id AS id
            """;
}
