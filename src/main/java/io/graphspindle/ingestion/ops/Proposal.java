package io.graphspindle.ingestion.ops;

import java.util.List;

/** The reconcile-stage envelope {@code {summary, operations[]}} after parsing. */
public record Proposal(String summary, List<MutationOperation> operations) {

    public Proposal {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }

    public static Proposal of(MutationOperation... ops) {
        return new Proposal(null, List.of(ops));
    }
}
