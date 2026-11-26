package io.graphspindle.ingestion.ops;

import java.util.Objects;

/**
 * Reference to an entity inside a proposal (ARCHITECTURE §4.1): exactly one of a local {@code ref}
 * defined by an earlier {@code create_entity}, or a candidate handle {@code id} from stage 2
 * ({@code k1}, {@code k2} … or {@code P0} for the speaker). Never a real id.
 */
public sealed interface EntityTarget {

    /** The raw string, for messages. */
    String token();

    static EntityTarget ref(String ref) {
        return new Ref(ref);
    }

    static EntityTarget handle(String id) {
        return new Handle(id);
    }

    /** Local reference to a {@code create_entity} of the same proposal. */
    record Ref(String ref) implements EntityTarget {

        public Ref {
            Objects.requireNonNull(ref, "ref");
        }

        @Override
        public String token() {
            return "ref:" + ref;
        }
    }

    /** Opaque per-ingestion candidate handle. */
    record Handle(String id) implements EntityTarget {

        public Handle {
            Objects.requireNonNull(id, "id");
        }

        @Override
        public String token() {
            return "id:" + id;
        }
    }
}
