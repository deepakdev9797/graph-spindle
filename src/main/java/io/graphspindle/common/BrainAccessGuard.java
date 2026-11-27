package io.graphspindle.common;

/**
 * Server-side authorization of brain access (ADR-033). Called at the start of every application
 * service method that takes a brain id (through {@code BrainService.requireActive}, or directly),
 * never in controllers or MCP tools, so REST and MCP share it. The brain id always comes from the
 * request path or tool argument, never from content or model output.
 *
 * <p>A brain the principal has no grant for is reported exactly like a brain that does not exist
 * ({@code BRAIN_NOT_FOUND}, same message), so keys cannot probe other brains. A visible brain with
 * too low a permission is {@code FORBIDDEN}.
 */
public final class BrainAccessGuard {

    private BrainAccessGuard() {
    }

    /**
     * @throws GraphSpindleException {@code UNAUTHENTICATED} without a principal, {@code BRAIN_NOT_FOUND}
     *     for an invisible brain, {@code FORBIDDEN} for an insufficient permission
     */
    public static void require(Actor actor, String brainId, Permission required) {
        requireAuthenticated(actor);
        Permission granted = actor.permissionOn(brainId).orElseThrow(() -> GraphSpindleException.brainNotFound(brainId));
        if (!granted.includes(required)) {
            throw new GraphSpindleException(ErrorCode.FORBIDDEN, "this API key has " + granted
                    + " permission on the brain; " + required + " is required");
        }
    }

    /** Global admin only (brain creation, keys across brains). */
    public static void requireGlobalAdmin(Actor actor) {
        requireAuthenticated(actor);
        if (!actor.admin()) {
            throw new GraphSpindleException(ErrorCode.FORBIDDEN, "this operation requires the admin key");
        }
    }

    public static void requireAuthenticated(Actor actor) {
        if (actor == null) {
            throw new GraphSpindleException(ErrorCode.UNAUTHENTICATED, "authentication required");
        }
    }
}
