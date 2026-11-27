package io.graphspindle.api.mcp;

import io.graphspindle.common.Actor;
import io.graphspindle.common.BrainAccessGuard;
import io.graphspindle.security.CurrentActor;
import io.modelcontextprotocol.common.McpTransportContext;

/**
 * Who calls an MCP tool (ADR-032, ADR-033). The transport's context extractor ({@link McpConfig})
 * copies the {@link Actor} that the API key filter put on the HTTP request
 * ({@link CurrentActor#REQUEST_ATTRIBUTE}) into the {@link McpTransportContext}; tools resolve it from
 * there, never from tool arguments, and pass it to the same services as REST, which authorize it.
 */
public final class McpCaller {

    private McpCaller() {
    }

    /** @throws io.graphspindle.common.GraphSpindleException {@code UNAUTHENTICATED} without a principal */
    public static Actor actor(McpTransportContext context) {
        Object principal = context == null ? null : context.get(CurrentActor.REQUEST_ATTRIBUTE);
        Actor actor = principal instanceof Actor a ? a : null;
        BrainAccessGuard.requireAuthenticated(actor);
        return actor;
    }
}
