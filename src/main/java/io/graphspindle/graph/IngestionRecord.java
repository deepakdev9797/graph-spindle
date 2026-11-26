package io.graphspindle.graph;

import java.time.Instant;

/**
 * A stored Ingestion node.
 *
 * @param result the serialized ingestion result (JSON, written after completion), used for idempotent
 *     replays; null while RECEIVED
 */
public record IngestionRecord(String id, String requestId, String contentHash, String contentType, int contentLength,
        String summary, String speakerType, String speakerName, String principal, String status, int opsProposed,
        int opsApplied, int opsRejected, String report, String llmProvider, String llmModel, String promptVersion,
        Instant occurredAt, Instant receivedAt, Instant completedAt, String rawContent,
        String result) {
}
