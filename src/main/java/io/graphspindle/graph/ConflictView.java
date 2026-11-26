package io.graphspindle.graph;

import java.time.Instant;
import java.util.List;

/**
 * A stored Conflict with what it involves.
 *
 * @param raisedByIngestionId the ingestion that raised it, or null (manual writes, merges)
 */
public record ConflictView(String id, String kind, String status, String description, Instant createdAt,
        Instant resolvedAt, String resolution, String resolvedBy, String resolutionNote, String raisedByIngestionId,
        List<Involved> involves) {

    public ConflictView {
        involves = List.copyOf(involves);
    }

    /** One INVOLVES edge: {@code target} is {@code Claim} or {@code Entity}. */
    public record Involved(String role, String target, String id) {
    }

    public boolean open() {
        return "OPEN".equals(status);
    }

    /** The id of the claim with that role, or null. */
    public String claim(String role) {
        return involves.stream().filter(i -> "Claim".equals(i.target()) && role.equals(i.role())).map(Involved::id)
                .findFirst().orElse(null);
    }

    /** The id of the entity with that role, or null. */
    public String entity(String role) {
        return involves.stream().filter(i -> "Entity".equals(i.target()) && role.equals(i.role())).map(Involved::id)
                .findFirst().orElse(null);
    }
}
