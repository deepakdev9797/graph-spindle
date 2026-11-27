package io.graphspindle.retrieval;

import io.graphspindle.brain.BrainService;
import io.graphspindle.common.Actor;
import io.graphspindle.common.Permission;
import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Normalizer;
import io.graphspindle.common.Paging;
import io.graphspindle.graph.EntityView;
import io.graphspindle.graph.GraphReader;
import io.graphspindle.graph.ScoredEntity;
import io.graphspindle.graph.SearchHits;
import io.graphspindle.knowledge.KnowledgeModel.EntityInfo;
import io.graphspindle.knowledge.KnowledgeViews;
import io.graphspindle.vocabulary.BrainVocabulary;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Deterministic search (ARCHITECTURE §9), no LLM: exact alias hits first (score 1.0), then fulltext
 * over entity names and descriptions, claim statements and source summaries, merged by entity. Only
 * ACTIVE entities of the brain are returned. Shared by REST and MCP ({@code search_knowledge}).
 */
@Service
public class SearchService {

    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 50;
    public static final int MAX_QUERY = 500;

    private final BrainService brains;
    private final GraphReader reader;

    public SearchService(BrainService brains, GraphReader reader) {
        this.brains = brains;
        this.reader = reader;
    }

    /** One entity found, why it was found, and a score in (0, 1]; 1.0 is an exact alias match. */
    public record SearchHit(EntityInfo entity, double score, List<String> matchedBy) {
    }

    /** Graph text in the hits is untrusted data. */
    public record SearchResult(String query, List<SearchHit> hits, boolean untrusted) {
    }

    public SearchResult search(Actor actor, String brainId, String query, List<String> types, Integer limit) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.READ);
        int n = Paging.limit(limit, DEFAULT_LIMIT, MAX_LIMIT);
        if (query == null || query.isBlank()) {
            throw GraphSpindleException.validation("q is required");
        }
        if (Normalizer.codePointLength(query) > MAX_QUERY) {
            throw new GraphSpindleException(ErrorCode.LIMIT_EXCEEDED, "q must be at most " + MAX_QUERY + " characters");
        }
        Set<String> typeFilter = types(brains.vocabulary(scope), types);
        Map<String, Acc> hits = new LinkedHashMap<>();
        String norm = Normalizer.normUntruncated(query);
        if (!norm.isEmpty()) {
            for (EntityView e : reader.entitiesByAlias(scope, norm)) {
                add(hits, e, 1.0, "alias");
            }
        }
        int fetch = n * 2;
        for (ScoredEntity s : reader.fulltextEntities(scope, query, fetch)) {
            add(hits, s.entity(), scaled(s.score(), 0.95), "fulltext");
        }
        Map<String, Double> byClaim = new LinkedHashMap<>();
        for (SearchHits.ClaimHit c : reader.fulltextClaims(scope, query, fetch)) {
            byClaim.merge(c.claim().subjectId(), scaled(c.score(), 0.8), Math::max);
        }
        Map<String, Double> bySource = new LinkedHashMap<>();
        for (SearchHits.SourceHit s : reader.fulltextSources(scope, query, fetch)) {
            s.entityIds().forEach(id -> bySource.merge(id, scaled(s.score(), 0.6), Math::max));
        }
        Set<String> missing = new LinkedHashSet<>(byClaim.keySet());
        missing.addAll(bySource.keySet());
        missing.removeAll(hits.keySet());
        Map<String, EntityView> loaded = reader.entitiesByIds(scope, missing);
        byClaim.forEach((id, score) -> entity(hits, loaded, id).ifPresent(e -> add(hits, e, score, "claim")));
        bySource.forEach((id, score) -> entity(hits, loaded, id).ifPresent(e -> add(hits, e, score, "source")));
        List<SearchHit> out = hits.values().stream()
                .filter(a -> "ACTIVE".equals(a.entity.status()))
                .filter(a -> typeFilter.isEmpty() || typeFilter.contains(a.entity.type()))
                .sorted(Comparator.comparingDouble((Acc a) -> a.score).reversed()
                        .thenComparing(a -> a.entity.name(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(a -> a.entity.id()))
                .limit(n)
                .map(a -> new SearchHit(KnowledgeViews.entity(a.entity), round(a.score), List.copyOf(a.matchedBy)))
                .toList();
        return new SearchResult(query, out, true);
    }

    static Set<String> types(BrainVocabulary vocabulary, List<String> types) {
        Set<String> out = new LinkedHashSet<>();
        if (types == null) {
            return out;
        }
        for (String t : types) {
            if (t == null || t.isBlank()) {
                continue;
            }
            if (!vocabulary.isEnabledType(t.strip())) {
                throw GraphSpindleException.validation("unknown entity type: " + Normalizer.truncate(t, 40));
            }
            out.add(t.strip());
        }
        return out;
    }

    private static java.util.Optional<EntityView> entity(Map<String, Acc> hits, Map<String, EntityView> loaded,
            String id) {
        Acc a = hits.get(id);
        return java.util.Optional.ofNullable(a != null ? a.entity : loaded.get(id));
    }

    /** Lucene scores are unbounded; map them into (0, weight) so tiers keep their order. */
    static double scaled(double luceneScore, double weight) {
        double s = Math.max(0, luceneScore);
        return weight * s / (1 + s);
    }

    private static double round(double score) {
        return Math.round(score * 1000) / 1000.0;
    }

    private static void add(Map<String, Acc> hits, EntityView e, double score, String reason) {
        Acc a = hits.computeIfAbsent(e.id(), k -> new Acc(e));
        a.score = Math.max(a.score, score);
        a.matchedBy.add(reason);
    }

    private static final class Acc {
        final EntityView entity;
        double score;
        final Set<String> matchedBy = new LinkedHashSet<>();

        Acc(EntityView entity) {
            this.entity = entity;
        }
    }
}
