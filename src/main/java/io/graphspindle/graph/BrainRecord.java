package io.graphspindle.graph;

import java.time.Instant;

/** A stored Brain node. {@code configJson} is opaque to the graph module (serialized {@code BrainConfig}). */
public record BrainRecord(String id, String name, String description, String configJson, long configVersion,
        String selfEntityId, String status, Instant createdAt, Instant updatedAt) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
