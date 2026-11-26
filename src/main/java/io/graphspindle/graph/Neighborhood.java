package io.graphspindle.graph;

import java.util.List;

/**
 * Entities reachable over projection edges, and the projection edges among them.
 *
 * @param nodes neighbours (the centre excluded) with their distance in hops
 * @param edges projection edges among the centre and the nodes; each carries the claim id it projects
 */
public record Neighborhood(EntityView center, List<Node> nodes, List<Edge> edges) {

    public Neighborhood {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public record Node(EntityView entity, int distance) {
    }

    public record Edge(String claimId, String predicate, String fromId, String toId, double confidence) {
    }
}
