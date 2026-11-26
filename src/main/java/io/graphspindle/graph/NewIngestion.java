package io.graphspindle.graph;

import java.time.Instant;
import java.util.Objects;

/** A submission recorded as RECEIVED before any knowledge is written (ARCHITECTURE §3, §6). */
public record NewIngestion(String requestId, String contentHash, String contentType, int contentLength,
        String speakerType, String speakerName, String principal, Instant occurredAt, String rawContent) {

    public NewIngestion {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(principal, "principal");
    }
}
