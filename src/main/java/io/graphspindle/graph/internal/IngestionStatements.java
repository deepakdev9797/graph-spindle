package io.graphspindle.graph.internal;

/** Cypher for Ingestion nodes. */
public final class IngestionStatements {

    private IngestionStatements() {
    }

    /** Locks the Brain node first, then re-reads its status, so this serializes with MARK_DELETING. */
    public static final String CREATE_RECEIVED = """
            MATCH (b:Brain {id: $brainId})
            SET b._lock = true REMOVE b._lock
            WITH b WHERE b.status = 'ACTIVE'
            CREATE (i:Ingestion)
            SET i = $props, i.brainId = $brainId, i.status = 'RECEIVED', i.dryRun = false, i.opsProposed = 0,
                i.opsApplied = 0, i.opsRejected = 0, i.receivedAt = datetime()
            RETURN i
            """;

    public static final String FIND_BY_ID = "MATCH (i:Ingestion {brainId: $brainId, id: $id}) RETURN i";

    public static final String FIND_BY_REQUEST_ID =
            "MATCH (i:Ingestion {brainId: $brainId, requestId: $requestId}) RETURN i";

    public static final String LOCK_RECEIVED = """
            MATCH (i:Ingestion {brainId: $brainId, id: $id})
            SET i._lock = true REMOVE i._lock
            RETURN i.status AS status
            """;

    public static final String COMPLETE = """
            MATCH (i:Ingestion {brainId: $brainId, id: $id})
            SET i += $props, i.completedAt = datetime()
            RETURN i
            """;

    public static final String MARK_FAILED = """
            MATCH (i:Ingestion {brainId: $brainId, id: $id})
            WHERE i.status = 'RECEIVED'
            SET i.status = 'FAILED', i.report = $report, i.completedAt = datetime()
            RETURN i
            """;
}
