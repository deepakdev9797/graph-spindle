package io.graphspindle.ingestion.validation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Output of {@link OperationValidator}: the ordered commands to apply, plus what was rejected and
 * what was adjusted.
 *
 * @param summary the (bounded, redacted) proposal summary
 * @param commands commands in proposal order
 * @param rejected rejected operations sorted by operation index
 * @param adjusted silent normalizations
 * @param refs every accepted local ref and what it resolved to
 */
public record ValidatedPlan(String summary, List<PlanCommand> commands, List<ValidationIssue> rejected,
        List<Adjustment> adjusted, Map<String, ResolvedEntity> refs) {

    public ValidatedPlan {
        commands = List.copyOf(commands);
        rejected = List.copyOf(rejected);
        adjusted = List.copyOf(adjusted);
        refs = Map.copyOf(refs);
    }

    /** Distinct rejection codes, for reporting and tests. */
    public Set<RejectionCode> rejectedCodes() {
        TreeSet<RejectionCode> codes = new TreeSet<>();
        rejected.forEach(r -> codes.add(r.code()));
        return codes;
    }

    public <T extends PlanCommand> List<T> commands(Class<T> type) {
        return commands.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
