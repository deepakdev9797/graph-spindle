package io.graphspindle.graph;

import java.time.Instant;

/** One EVIDENCED_BY edge: the ingestion that supports a claim, with its quote. */
public record EvidenceView(String ingestionId, String quote, double confidence, Instant recordedAt, String principal) {
}
