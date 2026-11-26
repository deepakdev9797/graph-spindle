package io.graphspindle.retrieval;

import io.graphspindle.brain.BrainService;
import io.graphspindle.common.Actor;
import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Normalizer;
import io.graphspindle.common.Paging;
import io.graphspindle.graph.EntityView;
import io.graphspindle.graph.GraphReader;
import io.graphspindle.graph.Neighborhood;
import io.graphspindle.knowledge.KnowledgeModel.EntityInfo;
import io.graphspindle.knowledge.KnowledgeViews;
import io.graphspindle.vocabulary.BrainVocabulary;
import io.graphspindle.vocabulary.RelationPredicateDef;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Neighbourhood / subgraph around an entity (ARCHITECTURE §9): projection edges only, restricted to
 * relation predicates of the brain vocabulary, depth 1–2 and at most 200 nodes, every node matched
 * with the brain id. Shared by REST and MCP ({@code get_neighbors}).
 */
@Service
public class NeighborhoodService {

    public static final int MAX_DEPTH = 2;
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_NODES = 200;
    static final int MAX_EDGES = 1000;

    private final BrainService brains;
    private final GraphReader reader;

    public NeighborhoodService(BrainService brains, GraphReader reader) {
        this.brains = brains;
        this.reader = reader;
    }

    public record Node(EntityInfo entity, int distance) {
    }

    /** A projection edge; {@code claimId} is the claim it projects (provenance via the claim). */
    public record Edge(String claimId, String predicate, String fromId, String toId, double confidence) {
    }

    /** @param truncated true when the node limit was reached */
    public record NeighborhoodResult(EntityInfo center, String redirectedFrom, int depth, List<Node> nodes,
            List<Edge> edges, boolean truncated, boolean untrusted) {
    }

    public NeighborhoodResult neighbors(Actor actor, String brainId, String entityId, Integer depth,
            List<String> predicates, Integer limit) {
        BrainScope scope = brains.requireActive(brainId);
        int d = depth == null ? 1 : depth;
        if (d < 1) {
            throw GraphSpindleException.validation("depth must be at least 1");
        }
        if (d > MAX_DEPTH) {
            throw new GraphSpindleException(ErrorCode.LIMIT_EXCEEDED, "depth must be at most " + MAX_DEPTH);
        }
        int n = Paging.limit(limit, DEFAULT_LIMIT, MAX_NODES);
        List<String> types = predicates(brains.vocabulary(scope), predicates);
        EntityView center = (Paging.plausibleId(entityId) ? reader.resolveEntity(scope, entityId)
                : java.util.Optional.<EntityView>empty())
                .orElseThrow(() -> GraphSpindleException.notFound("entity", entityId));
        Neighborhood hood = reader.neighbors(scope, center.id(), d, types, n, MAX_EDGES).orElseThrow(
                () -> GraphSpindleException.notFound("entity", entityId));
        return new NeighborhoodResult(KnowledgeViews.entity(hood.center()),
                center.id().equals(entityId) ? null : entityId, d,
                hood.nodes().stream().map(x -> new Node(KnowledgeViews.entity(x.entity()), x.distance())).toList(),
                hood.edges().stream().map(x -> new Edge(x.claimId(), x.predicate(), x.fromId(), x.toId(),
                        x.confidence())).toList(), hood.nodes().size() >= n, true);
    }

    static List<String> predicates(BrainVocabulary vocabulary, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return vocabulary.relationPredicates().stream().map(RelationPredicateDef::name).toList();
        }
        List<String> out = new ArrayList<>();
        for (String p : requested) {
            if (p == null || p.isBlank()) {
                continue;
            }
            if (vocabulary.relation(p.strip()).isEmpty()) {
                throw GraphSpindleException.validation("not a relation predicate of the brain vocabulary: "
                        + Normalizer.truncate(p, 40));
            }
            out.add(p.strip());
        }
        return out.isEmpty() ? vocabulary.relationPredicates().stream().map(RelationPredicateDef::name).toList() : out;
    }
}
