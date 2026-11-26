package io.graphspindle.graph;

import java.util.List;

/** The SUPERSEDES neighbours of a claim ({@code (new)-[:SUPERSEDES]->(old)}). */
public record ClaimLinks(String claimId, List<String> supersedes, List<String> supersededBy, String reason) {

    public ClaimLinks {
        supersedes = List.copyOf(supersedes);
        supersededBy = List.copyOf(supersededBy);
    }
}
