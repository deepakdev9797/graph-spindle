package io.graphspindle.common;

/**
 * Who calls an application service: the authenticated principal (an API key id, or {@code admin}).
 * REST and MCP pass the same value, so both are recorded and (from M9) authorized the same way by
 * {@code BrainAccessGuard}. Until authentication exists (M9) inbound adapters use {@link #LOCAL_ADMIN}.
 */
public record Actor(String id) {

    public static final Actor LOCAL_ADMIN = new Actor("admin");

    public Actor {
        if (id == null || id.isBlank() || id.length() > 100) {
            throw new IllegalArgumentException("actor id is required (at most 100 characters)");
        }
    }
}
