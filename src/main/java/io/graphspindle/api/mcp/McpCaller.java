package io.graphspindle.api.mcp;

import io.graphspindle.common.Actor;
import io.modelcontextprotocol.common.McpTransportContext;

/**
 * Who calls an MCP tool (ADR-032). The transport's context extractor ({@link McpConfig}) copies the
 * principal that the HTTP security layer put on the request ({@link #PRINCIPAL_ATTRIBUTE}) into the
 * {@link McpTransportContext}; tools resolve their {@link Actor} from it, never from tool arguments.
 * Until authentication exists (M9) nothing sets the attribute and every call acts as
 * {@link Actor#LOCAL_ADMIN}, exactly like REST.
 */
public final class McpCaller {

    /** Request attribute (and transport-context key) holding the authenticated principal id. */
    public static final String PRINCIPAL_ATTRIBUTE = "graphspindle.principal";

    private McpCaller() {
    }

    public static Actor actor(McpTransportContext context) {
        Object principal = context == null ? null : context.get(PRINCIPAL_ATTRIBUTE);
        if (principal instanceof String id && !id.isBlank()) {
            return new Actor(id);
        }
        return Actor.LOCAL_ADMIN;
    }
}
