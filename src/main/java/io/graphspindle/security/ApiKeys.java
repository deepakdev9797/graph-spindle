package io.graphspindle.security;

import io.graphspindle.common.Permission;
import io.graphspindle.graph.ApiKeyRecord;
import java.time.Instant;
import java.util.List;

/** Request and response records of API key management (ADR-033). Secrets never appear in views. */
public final class ApiKeys {

    private ApiKeys() {
    }

    /** One brain and the permission on it. */
    public record Grant(String brainId, Permission permission) {
    }

    /** Global admin: a key with grants on one or more brains. */
    public record NewApiKey(String name, List<Grant> grants, Instant expiresAt) {
    }

    /** Brain admin: a key for this brain only. */
    public record NewBrainApiKey(String name, Permission permission, Instant expiresAt) {
    }

    /** A key as shown to admins: never the secret or its hash. */
    public record ApiKeyInfo(String id, String name, String status, List<Grant> grants, String createdBy,
            Instant createdAt, Instant expiresAt, Instant lastUsedAt, Instant revokedAt) {

        static ApiKeyInfo of(ApiKeyRecord r, Instant now) {
            String status = r.revokedAt() != null ? "REVOKED"
                    : r.expiresAt() != null && !r.expiresAt().isAfter(now) ? "EXPIRED" : "ACTIVE";
            List<Grant> grants = r.grants().entrySet().stream()
                    .map(e -> new Grant(e.getKey(), Permission.valueOf(e.getValue()))).toList();
            return new ApiKeyInfo(r.id(), r.name(), status, grants, r.createdBy(), r.createdAt(), r.expiresAt(),
                    r.lastUsedAt(), r.revokedAt());
        }
    }

    /** The creation result: {@code apiKey} is the full token, shown exactly once. */
    public record CreatedApiKey(ApiKeyInfo key, String apiKey, String notice) {

        @Override
        public String toString() {
            return "CreatedApiKey[key=" + key + ", apiKey=<redacted>]";
        }
    }
}
