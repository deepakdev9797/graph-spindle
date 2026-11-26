package io.graphspindle.brain;

import java.time.Instant;

/** A brain as seen by callers. */
public record Brain(String id, String name, String description, String status, long configVersion,
        String selfEntityId, BrainConfig config, Instant createdAt, Instant updatedAt) {
}
