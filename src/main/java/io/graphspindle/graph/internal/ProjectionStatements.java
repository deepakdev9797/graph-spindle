package io.graphspindle.graph.internal;

/** Projection edges (SCHEMA §9.3). The relationship type is a whitelisted vocabulary predicate. */
public final class ProjectionStatements {

    private ProjectionStatements() {
    }

    public static final String PROJECT_RELATION = """
            MATCH (c:Claim {brainId: $brainId, id: $claimId})-[:SUBJECT {brainId: $brainId}]->(s:Entity {brainId: $brainId})
            MATCH (c)-[:OBJECT {brainId: $brainId}]->(o:Entity {brainId: $brainId})
            MERGE (s)-[r:$($predicate) {brainId: $brainId, claimId: $claimId}]->(o)
            SET r.confidence = c.confidence, r.validFrom = c.validFrom, r.recordedAt = c.recordedAt
            RETURN count(r) AS n
            """;

    public static final String UNPROJECT_RELATION = """
            MATCH (s:Entity {brainId: $brainId, id: $subjectId})-[r {brainId: $brainId, claimId: $claimId}]->()
            DELETE r
            RETURN count(*) AS n
            """;
}
