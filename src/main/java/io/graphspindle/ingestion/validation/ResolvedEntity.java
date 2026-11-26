package io.graphspindle.ingestion.validation;

import java.util.Objects;

/** An entity target after validation: an existing entity (real id) or one created by this plan. */
public sealed interface ResolvedEntity {

    String type();

    /** True when both denote the same entity. */
    boolean sameAs(ResolvedEntity other);

    /** Existing entity, resolved from a candidate handle (or from a create that matched one). */
    record Existing(String entityId, String type, String handle) implements ResolvedEntity {

        public Existing {
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(type, "type");
        }

        @Override
        public boolean sameAs(ResolvedEntity other) {
            return other instanceof Existing e && e.entityId.equals(entityId);
        }
    }

    /**
     * Entity created by this plan. At apply time it is MERGEd by {@code canonicalKey}, so it may
     * still turn out to be an existing node (concurrent creation, ARCHITECTURE §3 stage 5).
     */
    record New(String ref, String type, String canonicalKey) implements ResolvedEntity {

        public New {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(canonicalKey, "canonicalKey");
        }

        @Override
        public boolean sameAs(ResolvedEntity other) {
            return other instanceof New n && n.canonicalKey.equals(canonicalKey);
        }
    }
}
