package io.graphspindle.graph;

import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Ids;
import io.graphspindle.graph.internal.GraphTransactions;
import io.graphspindle.graph.internal.IngestionStatements;
import io.graphspindle.graph.internal.P;
import io.graphspindle.graph.internal.Rows;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.neo4j.driver.exceptions.Neo4jException;
import org.springframework.stereotype.Component;

/**
 * Ingestion records. {@link #createReceived} and {@link #markFailed} run in their own transactions;
 * the transition to APPLIED happens inside {@link GraphStore#apply}.
 */
@Component
public class IngestionStore {

    private final GraphTransactions tx;

    public IngestionStore(GraphTransactions tx) {
        this.tx = tx;
    }

    /**
     * Inserts a RECEIVED ingestion. The {@code (brainId, requestId)} constraint makes this the
     * idempotency gate: a second insert with the same requestId fails with {@code IN_PROGRESS}.
     */
    public IngestionRecord createReceived(BrainScope scope, NewIngestion n) {
        Map<String, Object> props = P.of("id", Ids.newId(), "requestId", n.requestId(), "contentHash", n.contentHash(),
                "contentType", n.contentType(), "contentLength", n.contentLength(), "speakerType", n.speakerType(),
                "speakerName", n.speakerName(), "principal", n.principal(), "occurredAt",
                n.occurredAt() == null ? null : n.occurredAt().atZone(ZoneOffset.UTC), "rawContent", n.rawContent());
        try {
            return tx.write(scope, t -> t.run(IngestionStatements.CREATE_RECEIVED, P.of("props", props)).list()
                    .stream().findFirst().map(r -> Rows.ingestion(r.get("i")))
                    .orElseThrow(() -> GraphSpindleException.brainNotFound(scope.brainId())));
        } catch (Neo4jException e) {
            if (GraphTransactions.isConstraintViolation(e)) {
                throw new GraphSpindleException(ErrorCode.IN_PROGRESS, "an ingestion with this requestId exists",
                        Map.of("requestId", n.requestId()), e);
            }
            throw e;
        }
    }

    public Optional<IngestionRecord> find(BrainScope scope, String id) {
        return tx.read(scope, t -> t.run(IngestionStatements.FIND_BY_ID, P.of("id", id)).list().stream().findFirst()
                .map(r -> Rows.ingestion(r.get("i"))));
    }

    public Optional<IngestionRecord> findByRequestId(BrainScope scope, String requestId) {
        return tx.read(scope, t -> t.run(IngestionStatements.FIND_BY_REQUEST_ID, P.of("requestId", requestId)).list()
                .stream().findFirst().map(r -> Rows.ingestion(r.get("i"))));
    }

    /** Records a failure after the apply transaction rolled back. Only a RECEIVED ingestion can fail. */
    public boolean markFailed(BrainScope scope, String id, String report) {
        return tx.write(scope, t -> !t.run(IngestionStatements.MARK_FAILED, P.of("id", id, "report", report)).list()
                .isEmpty());
    }

    /** Stores the serialized result of a completed ingestion (APPLIED, NO_CHANGES or FAILED). */
    public boolean storeResult(BrainScope scope, String id, String resultJson) {
        return tx.write(scope, t -> !t.run(IngestionStatements.STORE_RESULT, P.of("id", id, "result", resultJson))
                .list().isEmpty());
    }

    /** The newest APPLIED/NO_CHANGES ingestion with this content hash received within {@code hours}. */
    public Optional<IngestionRecord> findRecentByContentHash(BrainScope scope, String contentHash, int hours) {
        return tx.read(scope, t -> t.run(IngestionStatements.FIND_RECENT_BY_HASH, P.of("contentHash", contentHash,
                "hours", (long) hours)).list().stream().findFirst().map(r -> Rows.ingestion(r.get("i"))));
    }
}
