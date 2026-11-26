package io.graphspindle.graph;

import java.time.Instant;

/** A stored SourceRef: a reference (never dereferenced) with a summary. */
public record SourceView(String id, String kind, String locator, String title, String summary, String mediaType,
        Instant createdAt, Instant lastSeenAt) {
}
