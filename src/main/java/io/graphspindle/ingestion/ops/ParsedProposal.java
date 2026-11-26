package io.graphspindle.ingestion.ops;

import io.graphspindle.ingestion.validation.ValidationIssue;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Output of {@link ProposalParser}.
 *
 * @param proposal the operations that could be mapped to typed records
 * @param wireIndexes for each operation in {@code proposal.operations()}, its index in the model's
 *     {@code operations} array (so issues always point at the model's numbering)
 * @param rejected operations dropped while parsing ({@code UNKNOWN_OP}, {@code UNKNOWN_FIELD},
 *     {@code INVALID_VALUE}), with wire indexes
 * @param unknownFields every unknown property seen, as a path such as {@code operations[2].brainId}
 */
public record ParsedProposal(Proposal proposal, List<Integer> wireIndexes, List<ValidationIssue> rejected,
        List<String> unknownFields) {

    public ParsedProposal {
        wireIndexes = List.copyOf(wireIndexes);
        rejected = List.copyOf(rejected);
        unknownFields = List.copyOf(unknownFields);
        if (wireIndexes.size() != proposal.operations().size()) {
            throw new IllegalArgumentException("one wire index per operation is required");
        }
    }

    /** Wraps an already typed proposal (e.g. built in tests): wire index = list index. */
    public static ParsedProposal of(Proposal proposal) {
        return new ParsedProposal(proposal, IntStream.range(0, proposal.operations().size()).boxed().toList(),
                List.of(), List.of());
    }

    /** Number of operations the model emitted, valid or not. */
    public int wireOperationCount() {
        return proposal.operations().size() + rejected.size();
    }
}
