package io.graphspindle.security;

import io.graphspindle.brain.BrainService;
import io.graphspindle.common.Actor;
import io.graphspindle.common.BrainAccessGuard;
import io.graphspindle.common.BrainScope;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Permission;
import io.graphspindle.graph.ApiKeyRecord;
import io.graphspindle.graph.ApiKeyStore;
import io.graphspindle.security.ApiKeys.ApiKeyInfo;
import io.graphspindle.security.ApiKeys.CreatedApiKey;
import io.graphspindle.security.ApiKeys.Grant;
import io.graphspindle.security.ApiKeys.NewApiKey;
import io.graphspindle.security.ApiKeys.NewBrainApiKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * API key verification and management (ADR-033). Keys are stored as {@code sha256(secret)} only; the
 * full token is returned once, on creation. The bootstrap admin key comes from configuration and only
 * its hash is kept in memory. Management is REST-only (no MCP tools) and authorized here: global
 * operations need the admin, per-brain operations {@code ADMIN} on that brain.
 */
@Service
public class ApiKeyService {

    static final int MAX_NAME = 100;
    static final int MAX_GRANTS = 50;
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
    static final String NOTICE = "Store this API key now: it is shown only once and cannot be recovered.";
    private static final Pattern KEY_ID = Pattern.compile("[0-9a-f]{16}");
    /** Compared against when the key id is unknown, so lookups of unknown ids cost the same. */
    private static final byte[] DUMMY = ApiKeyTokens.sha256("graphspindle-unknown-key");

    private final ApiKeyStore store;
    private final BrainService brains;
    private final byte[] adminKeyHash;
    private final Clock clock;
    private final Map<String, Instant> lastTouched = new ConcurrentHashMap<>();

    @Autowired
    public ApiKeyService(ApiKeyStore store, BrainService brains, SecurityProperties properties) {
        this(store, brains, properties, Clock.systemUTC());
    }

    ApiKeyService(ApiKeyStore store, BrainService brains, SecurityProperties properties, Clock clock) {
        this.store = store;
        this.brains = brains;
        this.adminKeyHash = properties.hasAdminKey() ? ApiKeyTokens.sha256(properties.adminApiKey()) : null;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ authentication

    /** The principal of a presented token, or empty when it is unknown, wrong, revoked or expired. */
    public Optional<Actor> authenticate(String token) {
        if (token == null || token.isEmpty() || token.length() > 200) {
            return Optional.empty();
        }
        if (adminKeyHash != null && ApiKeyTokens.matches(adminKeyHash, ApiKeyTokens.sha256(token))) {
            return Optional.of(Actor.LOCAL_ADMIN);
        }
        Optional<ApiKeyTokens.Parsed> parsed = ApiKeyTokens.parse(token);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        byte[] presented = ApiKeyTokens.sha256(parsed.get().secret());
        Optional<ApiKeyRecord> found = store.find(parsed.get().keyId());
        byte[] expected = found.map(r -> HexFormat.of().parseHex(r.secretHash())).orElse(DUMMY);
        if (!ApiKeyTokens.matches(expected, presented) || found.isEmpty()) {
            return Optional.empty();
        }
        ApiKeyRecord key = found.get();
        Instant now = clock.instant();
        if (key.revokedAt() != null || (key.expiresAt() != null && !key.expiresAt().isAfter(now))
                || key.grants().isEmpty()) {
            return Optional.empty();
        }
        touch(key.id(), now);
        Map<String, Permission> grants = new LinkedHashMap<>();
        key.grants().forEach((brain, permission) -> grants.put(brain, Permission.valueOf(permission)));
        return Optional.of(Actor.key(key.id(), grants));
    }

    private void touch(String keyId, Instant now) {
        Instant last = lastTouched.get(keyId);
        if (last == null || last.plus(TOUCH_INTERVAL).isBefore(now)) {
            lastTouched.put(keyId, now);
            store.touch(keyId);
        }
    }

    // ------------------------------------------------------------------ global management (admin)

    public CreatedApiKey create(Actor actor, NewApiKey request) {
        BrainAccessGuard.requireGlobalAdmin(actor);
        if (request == null) {
            throw GraphSpindleException.validation("request body is required");
        }
        if (request.grants() == null || request.grants().isEmpty() || request.grants().size() > MAX_GRANTS) {
            throw GraphSpindleException.validation("grants must list 1 to " + MAX_GRANTS + " brains");
        }
        Map<String, String> grants = new LinkedHashMap<>();
        for (Grant g : request.grants()) {
            if (g == null || g.brainId() == null || g.permission() == null) {
                throw GraphSpindleException.validation("every grant needs brainId and permission");
            }
            BrainScope scope = brains.requireActive(actor, g.brainId(), Permission.ADMIN);
            if (grants.put(scope.brainId(), g.permission().name()) != null) {
                throw GraphSpindleException.validation("duplicate grant for brain " + scope.brainId());
            }
        }
        return create(actor, request.name(), request.expiresAt(), grants);
    }

    public List<ApiKeyInfo> list(Actor actor) {
        BrainAccessGuard.requireGlobalAdmin(actor);
        Instant now = clock.instant();
        return store.listAll().stream().map(r -> ApiKeyInfo.of(r, now)).toList();
    }

    /** Revokes the whole key (every brain). Idempotent. */
    public ApiKeyInfo revoke(Actor actor, String keyId) {
        BrainAccessGuard.requireGlobalAdmin(actor);
        requireKeyId(keyId);
        return store.revoke(keyId).map(r -> ApiKeyInfo.of(r, clock.instant()))
                .orElseThrow(() -> GraphSpindleException.notFound("API key", keyId));
    }

    // ------------------------------------------------------------------ per-brain management (brain ADMIN)

    public CreatedApiKey createForBrain(Actor actor, String brainId, NewBrainApiKey request) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.ADMIN);
        if (request == null || request.permission() == null) {
            throw GraphSpindleException.validation("permission (READ, WRITE or ADMIN) is required");
        }
        return create(actor, request.name(), request.expiresAt(), Map.of(scope.brainId(), request.permission().name()));
    }

    /** Keys with a grant on the brain, showing only that brain's grant. */
    public List<ApiKeyInfo> listForBrain(Actor actor, String brainId) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.ADMIN);
        Instant now = clock.instant();
        return store.listForBrain(scope).stream().map(r -> ApiKeyInfo.of(r, now)).toList();
    }

    public ApiKeyInfo getForBrain(Actor actor, String brainId, String keyId) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.ADMIN);
        requireKeyId(keyId);
        return store.findForBrain(scope, keyId).map(r -> ApiKeyInfo.of(r, clock.instant()))
                .orElseThrow(() -> GraphSpindleException.notFound("API key", keyId));
    }

    /**
     * Removes the key's access to this brain; a key left without any brain is revoked. Other brains a
     * multi-brain key may access are untouched (only the admin revokes a key everywhere).
     */
    public ApiKeyInfo revokeForBrain(Actor actor, String brainId, String keyId) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.ADMIN);
        requireKeyId(keyId);
        return store.removeGrant(scope, keyId).map(r -> ApiKeyInfo.of(r, clock.instant()))
                .orElseThrow(() -> GraphSpindleException.notFound("API key", keyId));
    }

    // ------------------------------------------------------------------ helpers

    private CreatedApiKey create(Actor actor, String name, Instant expiresAt, Map<String, String> grants) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > MAX_NAME) {
            throw GraphSpindleException.validation("name is required (at most " + MAX_NAME + " characters)");
        }
        if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
            throw GraphSpindleException.validation("expiresAt must be in the future");
        }
        ApiKeyTokens.Generated g = ApiKeyTokens.generate();
        ApiKeyRecord r = store.create(g.keyId(), n, g.secretHash(), actor.id(), expiresAt, grants);
        return new CreatedApiKey(ApiKeyInfo.of(r, clock.instant()), g.token(), NOTICE);
    }

    private static void requireKeyId(String keyId) {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw GraphSpindleException.notFound("API key", keyId);
        }
    }
}
