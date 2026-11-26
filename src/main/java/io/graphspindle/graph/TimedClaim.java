package io.graphspindle.graph;

import java.time.Instant;
import java.util.List;

/**
 * A claim with its record-time facts, for point-in-time reads and history (ADR-026).
 *
 * @param claim the claim
 * @param recordedAt when the brain recorded it (transaction time)
 * @param statusChangedAt when its status last changed (equals {@code recordedAt} if never)
 * @param supersedes ids of the claims it superseded ({@code (this)-[:SUPERSEDES]->(old)})
 * @param supersedeReason the reason on the SUPERSEDES link, or null
 */
public record TimedClaim(ClaimView claim, Instant recordedAt, Instant statusChangedAt, List<String> supersedes,
        String supersedeReason) {

    public TimedClaim {
        supersedes = List.copyOf(supersedes);
    }
}
