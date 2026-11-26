package io.graphspindle.ingestion.ops;

import io.graphspindle.ingestion.validation.Adjustment;
import io.graphspindle.ingestion.validation.ValidationIssue;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Output of {@link ProposalParser}.
 *
 * @param proposal the operations that could be mapped to typed records
 * @param wireIndexes for each operation in {@code proposal.operations()}, its index in the model's
 *     {@code operations} array (so issues always point at the model's numbering); -1 marks an
 *     operation added by the engine (e.g. the speaker entity)
 * @param rejected operations dropped while parsing ({@code UNKNOWN_OP}, {@code UNKNOWN_FIELD},
 *     {@code INVALID_VALUE}), with wire indexes
 * @param unknownFields every unknown property seen, as a path such as {@code operations[2].brainId}
 * @param adjustments normalizations made while parsing (e.g. attribute fields dropped from a
 *     {@code create_entity}); reported only if their operation is accepted
 * @param droppedRefs local refs of {@code create_entity} operations the parser rejected, mapped to
 *     their wire index, so dependent operations are reported as {@code DEPENDENCY_REJECTED}
 */
public record ParsedProposal(Proposal proposal, List<Integer> wireIndexes, List<ValidationIssue> rejected,
        List<String> unknownFields, List<Adjustment> adjustments, Map<String, Integer> droppedRefs) {

    public ParsedProposal {
        wireIndexes = List.copyOf(wireIndexes);
        rejected = List.copyOf(rejected);
        unknownFields = List.copyOf(unknownFields);
        adjustments = adjustments == null ? List.of() : List.copyOf(adjustments);
        droppedRefs = droppedRefs == null ? Map.of() : Map.copyOf(droppedRefs);
        if (wireIndexes.size() != proposal.operations().size()) {
            throw new IllegalArgumentException("one wire index per operation is required");
        }
    }

    public ParsedProposal(Proposal proposal, List<Integer> wireIndexes, List<ValidationIssue> rejected,
            List<String> unknownFields) {
        this(proposal, wireIndexes, rejected, unknownFields, List.of(), Map.of());
    }

    /** Wraps an already typed proposal (e.g. built in tests): wire index = list index. */
    public static ParsedProposal of(Proposal proposal) {
        return new ParsedProposal(proposal, IntStream.range(0, proposal.operations().size()).boxed().toList(),
                List.of(), List.of());
    }

    /** Number of operations the model emitted, valid or not (engine-added operations excluded). */
    public int wireOperationCount() {
        return (int) wireIndexes.stream().filter(i -> i >= 0).count() + rejected.size();
    }
}
