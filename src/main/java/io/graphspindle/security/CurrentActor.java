package io.graphspindle.security;

import io.graphspindle.common.Actor;
import io.graphspindle.common.BrainAccessGuard;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The authenticated {@link Actor} of the current HTTP request (ADR-033). {@link ApiKeyAuthenticationFilter}
 * stores it in the Spring Security context (read by REST controllers) and in the request attribute
 * {@link #REQUEST_ATTRIBUTE}, which the MCP transport copies into the tool's transport context.
 */
public final class CurrentActor {

    /** Request attribute (and MCP transport-context key) holding the authenticated {@link Actor}. */
    public static final String REQUEST_ATTRIBUTE = "graphspindle.actor";

    private CurrentActor() {
    }

    /** @throws io.graphspindle.common.GraphSpindleException {@code UNAUTHENTICATED} without a principal */
    public static Actor require() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        Actor actor = a != null && a.getPrincipal() instanceof Actor x ? x : null;
        BrainAccessGuard.requireAuthenticated(actor);
        return actor;
    }
}
