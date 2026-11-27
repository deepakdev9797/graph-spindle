package io.graphspindle.common;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Who calls an application service (ADR-033): the authenticated principal and what it may do. REST
 * and MCP resolve the same value from the authenticated request, and every service method that takes
 * a brain checks it with {@link BrainAccessGuard}, so both interfaces are authorized identically.
 *
 * @param id the principal id recorded in provenance: an API key id, or {@code admin} for the
 *     bootstrap admin key (and for the unauthenticated loopback development mode)
 * @param admin global admin: every brain with every permission, brain creation and all API keys
 * @param grants brain id to permission, for non-admin principals
 */
public record Actor(String id, boolean admin, Map<String, Permission> grants) {

    /** The bootstrap admin principal (and the local development principal when security is disabled). */
    public static final Actor LOCAL_ADMIN = new Actor("admin", true, Map.of());

    public Actor {
        if (id == null || id.isBlank() || id.length() > 100) {
            throw new IllegalArgumentException("actor id is required (at most 100 characters)");
        }
        grants = grants == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(grants));
    }

    /** A non-admin principal with the given brain grants. */
    public static Actor key(String id, Map<String, Permission> grants) {
        return new Actor(id, false, grants);
    }

    /** The permission on a brain, if any (global admins have {@code ADMIN} everywhere). */
    public Optional<Permission> permissionOn(String brainId) {
        if (admin) {
            return Optional.of(Permission.ADMIN);
        }
        return Optional.ofNullable(brainId == null ? null : grants.get(brainId));
    }

    /** Whether the brain is visible to this principal at all (any permission). */
    public boolean canSee(String brainId) {
        return permissionOn(brainId).isPresent();
    }
}
