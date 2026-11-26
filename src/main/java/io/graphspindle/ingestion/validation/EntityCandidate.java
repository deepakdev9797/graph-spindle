package io.graphspindle.ingestion.validation;

import java.util.Objects;
import java.util.Set;

/**
 * An existing entity offered to the reconcile stage under an opaque handle (ARCHITECTURE §3 stage 2).
 *
 * @param handle opaque per-ingestion handle ({@code k1} … or {@code P0} for the speaker)
 * @param entityId the real entity id (never shown to the LLM)
 * @param type the entity type
 * @param name display name
 * @param normalizedName {@code norm(name)}
 * @param canonicalKey the stored canonical key
 * @param aliasNorms normalized aliases (including the normalized name)
 * @param descriptionLocked true when a manual edit locked the description
 * @param mentionTypes extracted types of the mentions this candidate was retrieved for; empty for
 *     the speaker and for context-only candidates
 */
public record EntityCandidate(String handle, String entityId, String type, String name, String normalizedName,
        String canonicalKey, Set<String> aliasNorms, boolean descriptionLocked, Set<String> mentionTypes) {

    public EntityCandidate {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(canonicalKey, "canonicalKey");
        aliasNorms = aliasNorms == null ? Set.of() : Set.copyOf(aliasNorms);
        mentionTypes = mentionTypes == null ? Set.of() : Set.copyOf(mentionTypes);
    }
}
