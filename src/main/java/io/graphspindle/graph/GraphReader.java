package io.graphspindle.graph;

import io.graphspindle.common.BrainScope;
import io.graphspindle.graph.internal.AliasStore;
import io.graphspindle.graph.internal.ClaimStore;
import io.graphspindle.graph.internal.EntityStore;
import io.graphspindle.graph.internal.GraphTransactions;
import io.graphspindle.common.Page;
import io.graphspindle.graph.internal.Lucene;
import io.graphspindle.graph.internal.P;
import io.graphspindle.graph.internal.RetrievalStatements;
import io.graphspindle.graph.internal.Rows;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Brain-scoped read access (each call is one read transaction). Every query matches every node and
 * relationship with the scope's {@code brainId}, so an id from another brain is simply not found.
 */
@Component
public class GraphReader {

    private final GraphTransactions tx;
    private final EntityStore entities;
    private final AliasStore aliases;
    private final ClaimStore claims;

    public GraphReader(GraphTransactions tx, EntityStore entities, AliasStore aliases, ClaimStore claims) {
        this.tx = tx;
        this.entities = entities;
        this.aliases = aliases;
        this.claims = claims;
    }

    public Optional<EntityView> entity(BrainScope scope, String id) {
        return tx.read(scope, t -> entities.findById(t, id));
    }

    /** The entity or the survivor it was merged into. */
    public Optional<EntityView> resolveEntity(BrainScope scope, String id) {
        return tx.read(scope, t -> entities.resolve(t, id));
    }

    public Optional<EntityView> entityByKey(BrainScope scope, String canonicalKey) {
        return tx.read(scope, t -> entities.findByKey(t, canonicalKey));
    }

    public List<EntityView> entitiesByAlias(BrainScope scope, String normalized) {
        return tx.read(scope, t -> aliases.lookup(t, normalized));
    }

    public List<String> aliasesOf(BrainScope scope, String entityId) {
        return tx.read(scope, t -> aliases.ofEntity(t, entityId));
    }

    public List<ScoredEntity> fulltextEntities(BrainScope scope, String text, int limit) {
        return tx.read(scope, t -> entities.fulltext(t, text, limit));
    }

    /** Fulltext with one-typo variants of longer words, for the ingestion candidate lookup. */
    public List<ScoredEntity> fulltextEntitiesFuzzy(BrainScope scope, String text, int limit) {
        return tx.read(scope, t -> entities.fulltext(t, text, limit, true));
    }

    /** Entities with an alias of this compact key (ADR-030), at most {@code limit}. */
    public List<EntityView> entitiesByCompactAlias(BrainScope scope, String compact, int limit) {
        return tx.read(scope, t -> aliases.lookupCompact(t, compact, limit));
    }

    public Optional<ClaimView> claim(BrainScope scope, String id) {
        return tx.read(scope, t -> claims.findById(t, id));
    }

    public List<ClaimView> claimsOfSubject(BrainScope scope, String subjectId) {
        return tx.read(scope, t -> claims.ofSubject(t, subjectId));
    }

    // ------------------------------------------------------------------ M5: entities

    /** Keyset page of entities by id; {@code type}/{@code status} null means any. */
    public Page<EntityView> listEntities(BrainScope scope, String type, String status, String after, int limit) {
        return tx.read(scope, t -> Page.of(t.run(RetrievalStatements.LIST_ENTITIES, P.of("type", type, "status", status,
                "after", after, "limit", limit + 1L)).list(r -> Rows.entity(r.get("n"))), limit, EntityView::id));
    }

    public Map<String, EntityView> entitiesByIds(BrainScope scope, Collection<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return tx.read(scope, t -> {
            Map<String, EntityView> m = new LinkedHashMap<>();
            t.run(RetrievalStatements.ENTITIES_BY_IDS, P.of("ids", List.copyOf(ids))).list(r -> Rows.entity(r.get("n")))
                    .forEach(e -> m.put(e.id(), e));
            return m;
        });
    }

    public List<AliasView> aliases(BrainScope scope, String entityId) {
        return tx.read(scope, t -> t.run(RetrievalStatements.ALIASES_OF, P.of("id", entityId)).list(r -> new AliasView(
                r.get("normalized").asString(), r.get("display").asString(null), r.get("origin").asString(null))));
    }

    public List<SourceView> sourcesDescribing(BrainScope scope, String entityId, int limit) {
        return tx.read(scope, t -> t.run(RetrievalStatements.SOURCES_DESCRIBING, P.of("id", entityId, "limit",
                (long) limit)).list(r -> Rows.source(r.get("s"))));
    }

    public List<String> openConflictsOfEntity(BrainScope scope, String entityId) {
        return tx.read(scope, t -> t.run(RetrievalStatements.OPEN_CONFLICTS_OF_ENTITY, P.of("id", entityId))
                .list(r -> r.get("id").asString()));
    }

    // ------------------------------------------------------------------ M5: claims

    /** Keyset page of claims by id; null filters mean any. */
    public Page<ClaimView> listClaims(BrainScope scope, String subjectId, String objectId, String predicate,
            String status, String after, int limit) {
        return tx.read(scope, t -> Page.of(t.run(RetrievalStatements.LIST_CLAIMS, P.of("subjectId", subjectId,
                "objectId", objectId, "predicate", predicate, "status", status, "after", after, "limit", limit + 1L))
                .list(r -> Rows.claim(r.get("c"))), limit, ClaimView::id));
    }

    /** Claims where the entity is subject or object, with one of the statuses, newest first. */
    /** Relation claims with the given statuses where the entity is subject or object, newest first. */
    public List<ClaimView> relationClaimsOfEntity(BrainScope scope, String entityId, List<String> statuses,
            int limit) {
        return tx.read(scope, t -> t.run(RetrievalStatements.RELATION_CLAIMS_OF_ENTITY, P.of("id", entityId,
                "statuses", statuses, "limit", (long) limit)).list(r -> Rows.claim(r.get("c"))));
    }

    public List<ClaimView> claimsOfEntity(BrainScope scope, String entityId, List<String> statuses, int limit) {
        return tx.read(scope, t -> t.run(RetrievalStatements.CLAIMS_OF_ENTITY, P.of("id", entityId, "statuses",
                statuses, "limit", (long) limit)).list(r -> Rows.claim(r.get("c"))));
    }

    /**
     * The record-time timeline of an entity: the newest {@code limit} claims recorded at or before
     * {@code asOf} (null: all) where it is, or was before a merge, subject or object, returned oldest
     * first (ADR-026).
     */
    public List<TimedClaim> claimTimeline(BrainScope scope, String entityId, java.time.Instant asOf, int limit) {
        List<TimedClaim> newestFirst = tx.read(scope, t -> t.run(RetrievalStatements.CLAIM_TIMELINE_OF_ENTITY,
                P.of("id", entityId, "asOf", asOf == null ? null : asOf.atZone(java.time.ZoneOffset.UTC), "limit",
                        (long) limit)).list(r -> {
                    var c = r.get("c");
                    java.time.Instant recorded = c.get("recordedAt").asZonedDateTime().toInstant();
                    java.time.Instant changed = c.get("statusChangedAt").isNull() ? recorded
                            : c.get("statusChangedAt").asZonedDateTime().toInstant();
                    return new TimedClaim(Rows.claim(c), recorded, changed, r.get("supersedes").asList(v -> v.asString()),
                            r.get("reason").asString(null), instantOrNull(r.get("supersededAt")),
                            c.get("originalSubjectId").asString(null), instantOrNull(c.get("subjectRepointedAt")),
                            c.get("originalObjectId").asString(null), instantOrNull(c.get("objectRepointedAt")));
                }));
        List<TimedClaim> out = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(out);
        return out;
    }

    private static java.time.Instant instantOrNull(org.neo4j.driver.Value v) {
        return v == null || v.isNull() ? null : v.asZonedDateTime().toInstant();
    }

    /** Record-time facts of an entity node: creation and, when merged, the merge instant. */
    public record EntityTimes(java.time.Instant createdAt, java.time.Instant mergedAt) {
    }

    /** When the entity node was created and merged, if it exists (the node itself, merges not followed). */
    public Optional<EntityTimes> entityTimes(BrainScope scope, String entityId) {
        return tx.read(scope, t -> t.run(RetrievalStatements.ENTITY_CREATED_AT, P.of("id", entityId)).list().stream()
                .findFirst().map(r -> new EntityTimes(instantOrNull(r.get("createdAt")),
                        instantOrNull(r.get("mergedAt")))));
    }

    /** The SUPERSEDES chain the claim belongs to, oldest first, each with its links. Empty if not found. */
    public List<ClaimLinked> claimHistory(BrainScope scope, String claimId) {
        return tx.read(scope, t -> {
            List<ClaimLinked> out = new ArrayList<>();
            for (ClaimView c : t.run(RetrievalStatements.CLAIM_HISTORY, P.of("id", claimId))
                    .list(r -> Rows.claim(r.get("c")))) {
                out.add(new ClaimLinked(c, links(t, c.id())));
            }
            return out;
        });
    }

    /** A claim with its links, evidence, cited sources and conflicts (provenance). */
    public Optional<ClaimProvenance> provenance(BrainScope scope, String claimId, int maxSources) {
        return tx.read(scope, t -> claims.findById(t, claimId).map(c -> new ClaimProvenance(c, links(t, c.id()),
                t.run(RetrievalStatements.EVIDENCE, P.of("id", c.id())).list(r -> new EvidenceView(
                        r.get("ingestionId").asString(), r.get("quote").asString(null), r.get("confidence").asDouble(0),
                        r.get("recordedAt").isNull() ? null : r.get("recordedAt").asZonedDateTime().toInstant(),
                        r.get("principal").asString(null))),
                citedSources(t, c.id(), maxSources),
                t.run(RetrievalStatements.CONFLICTS_OF_CLAIM, P.of("id", c.id(), "status", null))
                        .list(r -> r.get("id").asString()))));
    }

    /** Up to {@code perClaim} cited sources for each claim. */
    public Map<String, List<SourceView>> citedSources(BrainScope scope, Collection<String> claimIds, int perClaim) {
        return tx.read(scope, t -> {
            Map<String, List<SourceView>> m = new LinkedHashMap<>();
            claimIds.forEach(id -> m.put(id, citedSources(t, id, perClaim)));
            return m;
        });
    }

    /** OPEN conflict ids per claim. */
    public Map<String, List<String>> openConflictsOfClaims(BrainScope scope, Collection<String> claimIds) {
        return tx.read(scope, t -> {
            Map<String, List<String>> m = new LinkedHashMap<>();
            claimIds.forEach(id -> m.put(id, t.run(RetrievalStatements.CONFLICTS_OF_CLAIM, P.of("id", id, "status",
                    "OPEN")).list(r -> r.get("id").asString())));
            return m;
        });
    }

    private static List<SourceView> citedSources(io.graphspindle.graph.internal.ScopedTx t, String claimId, int limit) {
        return t.run(RetrievalStatements.CITED_SOURCES, P.of("id", claimId, "limit", (long) limit))
                .list(r -> Rows.source(r.get("s")));
    }

    private static ClaimLinks links(io.graphspindle.graph.internal.ScopedTx t, String claimId) {
        var r = t.run(RetrievalStatements.CLAIM_LINKS, P.of("id", claimId)).single();
        return new ClaimLinks(claimId, r.get("supersedes").asList(v -> v.asString()),
                r.get("supersededBy").asList(v -> v.asString()), r.get("reason").asString(null));
    }

    /** A claim of a history chain with its SUPERSEDES links. */
    public record ClaimLinked(ClaimView claim, ClaimLinks links) {
    }

    /** A claim and everything that explains where it came from. */
    public record ClaimProvenance(ClaimView claim, ClaimLinks links, List<EvidenceView> evidence,
            List<SourceView> sources, List<String> conflictIds) {
    }

    // ------------------------------------------------------------------ M5: conflicts and sources

    public Page<ConflictView> listConflicts(BrainScope scope, String status, String kind, String after, int limit) {
        return tx.read(scope, t -> Page.of(t.run(RetrievalStatements.LIST_CONFLICTS, P.of("status", status, "kind", kind,
                "after", after, "limit", limit + 1L)).list(r -> Rows.conflict(r.get("x"), r.get("involves"),
                        r.get("raisedBy"))), limit, ConflictView::id));
    }

    public Optional<ConflictView> conflict(BrainScope scope, String id) {
        return tx.read(scope, t -> t.run(RetrievalStatements.CONFLICT, P.of("id", id)).list().stream().findFirst()
                .map(r -> Rows.conflict(r.get("x"), r.get("involves"), r.get("raisedBy"))));
    }

    public Page<SourceView> listSources(BrainScope scope, String after, int limit) {
        return tx.read(scope, t -> Page.of(t.run(RetrievalStatements.LIST_SOURCES, P.of("after", after, "limit",
                limit + 1L)).list(r -> Rows.source(r.get("s"))), limit, SourceView::id));
    }

    /** A source with the entities it describes and the claims citing it (each list bounded by {@code limit}). */
    public Optional<SourceUse> source(BrainScope scope, String id, int limit) {
        return tx.read(scope, t -> t.run(RetrievalStatements.SOURCE, P.of("id", id)).list().stream().findFirst()
                .map(r -> new SourceUse(Rows.source(r.get("s")),
                        t.run(RetrievalStatements.SOURCE_DESCRIBES, P.of("id", id, "limit", (long) limit))
                                .list(x -> x.get("id").asString()),
                        t.run(RetrievalStatements.SOURCE_CITED_BY, P.of("id", id, "limit", (long) limit))
                                .list(x -> x.get("id").asString()))));
    }

    public record SourceUse(SourceView source, List<String> describesEntityIds, List<String> citedByClaimIds) {
    }

    // ------------------------------------------------------------------ M5: search and neighbourhood

    public List<SearchHits.ClaimHit> fulltextClaims(BrainScope scope, String text, int limit) {
        String query = Lucene.scoped(scope.brainId(), text, "statement");
        if (query.isEmpty()) {
            return List.of();
        }
        return tx.read(scope, t -> t.run(RetrievalStatements.FULLTEXT_CLAIMS, P.of("query", query, "limit",
                (long) limit, "oversample", limit * 5L)).list(r -> new SearchHits.ClaimHit(Rows.claim(r.get("c")),
                        r.get("score").asDouble())));
    }

    public List<SearchHits.SourceHit> fulltextSources(BrainScope scope, String text, int limit) {
        String query = Lucene.scoped(scope.brainId(), text, "title", "summary");
        if (query.isEmpty()) {
            return List.of();
        }
        List<SearchHits.SourceHit> hits = new ArrayList<>(tx.read(scope, t -> t.run(RetrievalStatements.FULLTEXT_SOURCES,
                P.of("query", query, "limit", (long) limit, "oversample", limit * 5L)).list(r -> new SearchHits.SourceHit(
                        Rows.source(r.get("s")), r.get("score").asDouble(),
                        r.get("entityIds").asList(v -> v.asString()).stream().distinct().toList()))));
        hits.sort(java.util.Comparator.comparingDouble(SearchHits.SourceHit::score).reversed());
        return hits;
    }

    /**
     * Entities within {@code depth} (1 or 2) hops over projection edges whose types are in
     * {@code predicates} (vocabulary relation predicates), and the edges among them. Empty if the
     * centre is not an entity of this brain.
     */
    public Optional<Neighborhood> neighbors(BrainScope scope, String entityId, int depth, List<String> predicates,
            int limit, int maxEdges) {
        if (depth < 1 || depth > 2) {
            throw new IllegalArgumentException("depth must be 1 or 2");
        }
        predicates.forEach(io.graphspindle.graph.internal.Tokens::storedPredicate);
        return tx.read(scope, t -> entities.findById(t, entityId).map(center -> {
            List<Neighborhood.Node> nodes = t.run(depth == 1 ? RetrievalStatements.NEIGHBORS_1
                    : RetrievalStatements.NEIGHBORS_2, P.of("id", center.id(), "types", predicates, "limit",
                            (long) limit)).list(r -> new Neighborhood.Node(Rows.entity(r.get("n")),
                                    r.get("distance").asInt()));
            List<String> ids = new ArrayList<>();
            ids.add(center.id());
            nodes.forEach(n -> ids.add(n.entity().id()));
            List<Neighborhood.Edge> edges = t.run(RetrievalStatements.EDGES_AMONG, P.of("ids", ids, "types", predicates,
                    "limit", (long) maxEdges)).list(r -> new Neighborhood.Edge(r.get("claimId").asString(),
                            r.get("predicate").asString(), r.get("fromId").asString(), r.get("toId").asString(),
                            r.get("confidence").asDouble(0)));
            return new Neighborhood(center, nodes, edges);
        }));
    }
}
