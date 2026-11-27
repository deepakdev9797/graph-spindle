package io.graphspindle.ingestion.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The immutable set of existing entities and claims an ingestion may reference, keyed by opaque
 * handle. Handles outside this set are rejected ({@code UNKNOWN_HANDLE}); that is what stops a
 * prompt-injected model from naming arbitrary nodes or nodes of another brain.
 */
public record CandidateSet(Map<String, EntityCandidate> entities, Map<String, ClaimCandidate> claims,
        java.util.Set<String> hidden) {

    public CandidateSet {
        entities = Collections.unmodifiableMap(new LinkedHashMap<>(entities));
        claims = Collections.unmodifiableMap(new LinkedHashMap<>(claims));
        hidden = hidden == null ? java.util.Set.of() : java.util.Set.copyOf(hidden);
    }

    public CandidateSet(Map<String, EntityCandidate> entities, Map<String, ClaimCandidate> claims) {
        this(entities, claims, java.util.Set.of());
    }

    /**
     * The same candidates, but only the handles the model was shown can be referenced (ADR-034):
     * candidates left out of the prompt for length are {@code UNKNOWN_HANDLE} like any invented
     * handle. The engine's own lookups (by key, name, id) still see every candidate.
     */
    public CandidateSet addressableOnly(java.util.Set<String> shownHandles) {
        if (shownHandles == null) {
            return this;
        }
        java.util.Set<String> h = new java.util.HashSet<>(entities.keySet());
        h.addAll(claims.keySet());
        h.removeAll(shownHandles);
        return new CandidateSet(entities, claims, h);
    }

    public static CandidateSet empty() {
        return new CandidateSet(Map.of(), Map.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<EntityCandidate> entity(String handle) {
        return hidden.contains(handle) ? Optional.empty() : Optional.ofNullable(entities.get(handle));
    }

    public Optional<ClaimCandidate> claim(String handle) {
        return hidden.contains(handle) ? Optional.empty() : Optional.ofNullable(claims.get(handle));
    }

    public Optional<EntityCandidate> byCanonicalKey(String canonicalKey) {
        return entities.values().stream().filter(e -> e.canonicalKey().equals(canonicalKey)).findFirst();
    }

    public List<EntityCandidate> byNormalizedName(String normalizedName) {
        return entities.values().stream().filter(e -> normalizedName.equals(e.normalizedName())).toList();
    }

    public Optional<EntityCandidate> byEntityId(String entityId) {
        return entities.values().stream().filter(e -> e.entityId().equals(entityId)).findFirst();
    }

    /** ACTIVE candidate claims with this subject and predicate. */
    public List<ClaimCandidate> claimsFor(String subjectEntityId, String predicate) {
        return claims.values().stream()
                .filter(c -> c.subjectEntityId().equals(subjectEntityId) && c.predicate().equals(predicate)).toList();
    }

    /** Builder keeping insertion order and rejecting duplicate handles. */
    public static final class Builder {

        private final Map<String, EntityCandidate> entities = new LinkedHashMap<>();
        private final Map<String, ClaimCandidate> claims = new LinkedHashMap<>();
        private final List<String> errors = new ArrayList<>();

        public Builder entity(EntityCandidate e) {
            if (entities.putIfAbsent(e.handle(), e) != null) {
                errors.add(e.handle());
            }
            return this;
        }

        public Builder claim(ClaimCandidate c) {
            if (claims.putIfAbsent(c.handle(), c) != null) {
                errors.add(c.handle());
            }
            return this;
        }

        public CandidateSet build() {
            if (!errors.isEmpty()) {
                throw new IllegalStateException("duplicate handles " + errors);
            }
            return new CandidateSet(entities, claims);
        }
    }
}
