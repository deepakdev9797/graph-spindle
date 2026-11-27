package io.graphspindle.graph;

import java.time.Instant;
import java.util.Map;

/**
 * A stored API key (ADR-033): never the secret, only its hash. {@code grants} maps brain id to
 * permission name ({@code READ}, {@code WRITE}, {@code ADMIN}).
 */
public record ApiKeyRecord(String id, String name, String secretHash, String createdBy, Instant createdAt,
        Instant expiresAt, Instant lastUsedAt, Instant revokedAt, Map<String, String> grants) {

    public ApiKeyRecord {
        grants = grants == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(grants));
    }
}
