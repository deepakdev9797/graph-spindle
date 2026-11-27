package io.graphspindle.graph.internal;

/**
 * Cypher for API keys (ADR-033). An {@code :ApiKey} node is global (it has no {@code brainId}, SCHEMA
 * I1); each brain it may access is an {@code :ApiKeyGrant} node that carries that brain's
 * {@code brainId}, so deleting a brain deletes its grants with all its other nodes and a re-created
 * brain with the same id grants nothing to old keys. Statements that look up or revoke a key by its id
 * are unscoped and allowlisted in CypherScopingTest; per-brain statements are scoped.
 */
public final class ApiKeyStatements {

    private ApiKeyStatements() {
    }

    /** Allowlisted (unscoped): a key and its grants ({@code $grants}: maps with brainId, permission). */
    public static final String CREATE = """
            CREATE (k:ApiKey {id: $keyId, name: $name, secretHash: $secretHash, createdBy: $createdBy,
                    createdAt: datetime(), expiresAt: $expiresAt})
            FOREACH (g IN $grants |
                CREATE (:ApiKeyGrant {brainId: g.brainId, keyId: $keyId, permission: g.permission,
                        createdAt: datetime()}))
            RETURN k
            """;

    /** Allowlisted (unscoped): authentication lookup by key id. */
    public static final String FIND = """
            MATCH (k:ApiKey {id: $keyId})
            OPTIONAL MATCH (g:ApiKeyGrant {keyId: $keyId})
            RETURN k, collect(g {.brainId, .permission}) AS grants
            """;

    /** Allowlisted (unscoped): every key, for the global admin. */
    public static final String LIST_ALL = """
            MATCH (k:ApiKey)
            OPTIONAL MATCH (g:ApiKeyGrant {keyId: k.id})
            WITH k, collect(g {.brainId, .permission}) AS grants
            RETURN k, grants
            ORDER BY k.createdAt, k.id
            """;

    /** Keys with a grant on the brain; only that brain's grant is returned. */
    public static final String LIST_FOR_BRAIN = """
            MATCH (g:ApiKeyGrant {brainId: $brainId})
            MATCH (k:ApiKey {id: g.keyId})
            RETURN k, [g {.brainId, .permission}] AS grants
            ORDER BY k.createdAt, k.id
            """;

    /** The key as seen from one brain (only that brain's grant), or no row without a grant there. */
    public static final String FIND_FOR_BRAIN = """
            MATCH (g:ApiKeyGrant {brainId: $brainId, keyId: $keyId})
            MATCH (k:ApiKey {id: $keyId})
            RETURN k, [g {.brainId, .permission}] AS grants
            """;

    /** Allowlisted (unscoped): revokes the whole key (idempotent; the first revocation time is kept). */
    public static final String REVOKE = """
            MATCH (k:ApiKey {id: $keyId})
            SET k.revokedAt = coalesce(k.revokedAt, datetime())
            WITH k
            OPTIONAL MATCH (g:ApiKeyGrant {keyId: $keyId})
            RETURN k, collect(g {.brainId, .permission}) AS grants
            """;

    /**
     * Removes the key's grant on this brain; a key left without any grant is revoked. Returns the key
     * with the removed grant, or no row when the key has no grant on the brain.
     */
    public static final String REMOVE_GRANT = """
            MATCH (k:ApiKey {id: $keyId})
            MATCH (g:ApiKeyGrant {brainId: $brainId, keyId: $keyId})
            OPTIONAL MATCH (o:ApiKeyGrant {keyId: $keyId}) WHERE o.brainId <> $brainId
            WITH k, g, g {.brainId, .permission} AS removed, count(o) AS remaining
            DELETE g
            SET k.revokedAt = CASE WHEN remaining = 0 THEN coalesce(k.revokedAt, datetime()) ELSE k.revokedAt END
            RETURN k, [removed] AS grants
            """;

    /** Allowlisted (unscoped): records the last successful authentication. */
    public static final String TOUCH = """
            MATCH (k:ApiKey {id: $keyId})
            SET k.lastUsedAt = datetime()
            """;
}
