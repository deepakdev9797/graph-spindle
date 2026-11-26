package io.graphspindle.graph.internal;

/** Cypher for SourceRef nodes. Locators are stored, never dereferenced. */
public final class SourceStatements {

    private SourceStatements() {
    }

    public static final String UPSERT = """
            MATCH (i:Ingestion {brainId: $brainId, id: $ingestionId})
            MERGE (s:SourceRef {brainId: $brainId, kind: $kind, locatorNorm: $locatorNorm})
            ON CREATE SET s.id = $id, s.locator = $locator, s.createdAt = datetime()
            SET s.lastSeenAt = datetime(), s.title = coalesce($title, s.title), s.summary = coalesce($summary, s.summary),
                s.mediaType = coalesce($mediaType, s.mediaType),
                s.clientContentHash = coalesce($clientContentHash, s.clientContentHash)
            MERGE (s)-[:SUBMITTED_IN {brainId: $brainId}]->(i)
            RETURN s.id AS id
            """;

    public static final String DESCRIBES = """
            MATCH (s:SourceRef {brainId: $brainId, id: $sourceId}), (n:Entity {brainId: $brainId, id: $entityId})
            MERGE (s)-[r:DESCRIBES {brainId: $brainId}]->(n)
            ON CREATE SET r.claimless = true
            RETURN s.id AS id
            """;
}
