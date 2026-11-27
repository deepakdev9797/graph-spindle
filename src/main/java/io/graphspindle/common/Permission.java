package io.graphspindle.common;

/**
 * What an API key may do in one brain (ADR-033). Permissions are ordered: {@code ADMIN} includes
 * {@code WRITE}, which includes {@code READ}.
 * <ul>
 *   <li>{@code READ}: every retrieval, listing and status read of the brain.</li>
 *   <li>{@code WRITE}: additionally ingestion ({@code remember}), manual knowledge edits, corrections,
 *       merges and conflict resolution.</li>
 *   <li>{@code ADMIN}: additionally brain config and vocabulary changes, brain deletion, the
 *       projection check and the brain's API keys.</li>
 * </ul>
 * Creating brains and managing keys across brains needs the global admin (the bootstrap key).
 */
public enum Permission {
    READ,
    WRITE,
    ADMIN;

    public boolean includes(Permission required) {
        return compareTo(required) >= 0;
    }
}
