package io.graphspindle.graph;

import java.util.List;

/** Raw fulltext hits over claims and sources (entity hits are {@link ScoredEntity}). */
public final class SearchHits {

    private SearchHits() {
    }

    public record ClaimHit(ClaimView claim, double score) {
    }

    /** A source hit and the entities it relates to (DESCRIBES, or subjects of ACTIVE claims citing it). */
    public record SourceHit(SourceView source, double score, List<String> entityIds) {

        public SourceHit {
            entityIds = List.copyOf(entityIds);
        }
    }
}
