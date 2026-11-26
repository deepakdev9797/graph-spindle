package io.graphspindle.ingestion.pipeline;

import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ClaimKind;
import io.graphspindle.common.Normalizer;
import io.graphspindle.resolution.NameMatch;
import io.graphspindle.common.Origin;
import io.graphspindle.common.Polarity;
import io.graphspindle.graph.ClaimView;
import io.graphspindle.graph.EntityView;
import io.graphspindle.graph.GraphReader;
import io.graphspindle.graph.ScoredEntity;
import io.graphspindle.ingestion.pipeline.IngestionPrompts.PromptClaim;
import io.graphspindle.ingestion.pipeline.IngestionPrompts.PromptEntity;
import io.graphspindle.ingestion.validation.CandidateSet;
import io.graphspindle.ingestion.validation.ClaimCandidate;
import io.graphspindle.ingestion.validation.EntityCandidate;
import io.graphspindle.ingestion.validation.OperationValidator;
import io.graphspindle.vocabulary.BrainVocabulary;
import io.graphspindle.vocabulary.ValueType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Stage 2 (ARCHITECTURE §3): deterministic candidate lookup in <b>this brain only</b>. For every
 * extracted mention: exact alias lookup of {@code norm(name)} and of each alias, the same for their
 * compact keys (case, spacing, punctuation and "The" ignored), then fulltext top 5 with one-typo
 * variants, filtered to ACTIVE entities of a compatible type (the mention type may be a type
 * synonym). Each mention's candidates are ranked by a deterministic score (ADR-030). For each candidate up to 10 ACTIVE claims (newest
 * first) plus all its ACTIVE claims for predicates the extraction mentions. Objects of those claims
 * that are not candidates are added as context-only candidates so the claims can be shown.
 *
 * <p>Handles are assigned in discovery order: {@code P0} for the speaker, {@code k1}, {@code k2} …
 * for entities and {@code c1}, {@code c2} … for claims. Real ids never reach the prompt.
 */
@Component
public class CandidateStage {

    static final int FULLTEXT_K = 5;
    static final int CLAIMS_PER_ENTITY = 10;
    static final int MAX_ENTITIES = 40;
    static final int MAX_CLAIMS = 120;
    /** Entities read per compact-key lookup. */
    static final int COMPACT_K = 10;
    /** Minimum score of a compact-key match (same name up to case, spacing, punctuation, "The"). */
    static final double COMPACT_SCORE = 0.95;

    private final GraphReader reader;

    public CandidateStage(GraphReader reader) {
        this.reader = reader;
    }

    /**
     * @param speaker the entity "I" refers to, or null when the named speaker does not exist yet
     * @param speakerLabel how the speaker is shown to the model
     */
    public record Candidates(CandidateSet set, List<PromptEntity> entities, List<PromptClaim> claims,
            List<MentionMatches> matches) {

        public Candidates(CandidateSet set, List<PromptEntity> entities, List<PromptClaim> claims) {
            this(set, entities, claims, List.of());
        }
    }

    /**
     * A candidate for one mention with its deterministic score (ADR-030).
     *
     * @param via how it was found: {@code ALIAS} (exact normalized alias), {@code COMPACT} (same
     *     compact key), {@code FULLTEXT}
     * @param score 1.0 for an exact alias, else the best {@link NameMatch} similarity of the mention's
     *     names to the candidate's name and aliases (at least 0.95 for a compact match)
     */
    public record Match(String handle, String entityId, String via, double score) {
    }

    /** The ranked candidates of one extracted mention (best first). */
    public record MentionMatches(String name, String type, List<Match> ranked) {

        public Optional<Match> best() {
            return ranked.isEmpty() ? Optional.empty() : Optional.of(ranked.get(0));
        }
    }

    public Candidates find(BrainScope scope, BrainVocabulary vocabulary, Extraction extraction, EntityView speaker,
            String speakerLabel) {
        Map<String, Found> byId = new LinkedHashMap<>();
        if (speaker != null) {
            byId.put(speaker.id(), new Found(speaker, OperationValidator.SPEAKER_HANDLE, true));
        }
        List<Lookup> lookups = new ArrayList<>();
        for (Extraction.Mention m : extraction.mentions()) {
            // the mention type may be a type name in another case or a synonym ("Company")
            String type = vocabulary.typeFor(m.type()).orElse(null);
            Map<String, String> hits = new LinkedHashMap<>(); // entity id -> via
            Map<String, EntityView> views = new LinkedHashMap<>();
            Set<String> names = new LinkedHashSet<>();
            names.add(m.name());
            names.addAll(m.aliases());
            for (String n : names) {
                String norm = Normalizer.norm(n);
                if (!norm.isEmpty() && !norm.startsWith("@")) {
                    reader.entitiesByAlias(scope, norm).forEach(e -> hit(hits, views, e, "ALIAS"));
                    reader.entitiesByCompactAlias(scope, Normalizer.compact(n), COMPACT_K)
                            .forEach(e -> hit(hits, views, e, "COMPACT"));
                }
            }
            for (ScoredEntity s : reader.fulltextEntitiesFuzzy(scope, m.name(), FULLTEXT_K)) {
                hit(hits, views, s.entity(), "FULLTEXT");
            }
            Lookup lookup = new Lookup(m, type, new ArrayList<>());
            lookups.add(lookup);
            for (Map.Entry<String, String> h : hits.entrySet()) {
                Optional<EntityView> live = active(scope, views.get(h.getKey()));
                if (live.isEmpty() || (type != null && !vocabulary.compatible(type, live.get().type()))) {
                    continue;
                }
                if (byId.size() >= MAX_ENTITIES && !byId.containsKey(live.get().id())) {
                    break;
                }
                Found f = byId.computeIfAbsent(live.get().id(), id -> new Found(live.get(), null, false));
                if (type != null && !f.self) {
                    f.mentionTypes.add(type);
                }
                lookup.found().add(Map.entry(f, h.getValue()));
            }
        }

        // handles for entities found so far
        int k = 0;
        for (Found f : byId.values()) {
            if (f.handle == null) {
                f.handle = "k" + (++k);
            }
        }

        // claims of the candidates
        Map<String, ClaimView> claims = new LinkedHashMap<>();
        for (Found f : List.copyOf(byId.values())) {
            for (ClaimView c : reader.claimsOfEntity(scope, f.entity.id(), List.of("ACTIVE"), CLAIMS_PER_ENTITY)) {
                claims.putIfAbsent(c.id(), c);
            }
            if (!extraction.predicates().isEmpty()) {
                for (ClaimView c : reader.claimsOfSubject(scope, f.entity.id())) {
                    if (c.active() && extraction.predicates().contains(c.predicate())) {
                        claims.putIfAbsent(c.id(), c);
                    }
                }
            }
        }
        // context-only entities referenced by those claims
        Set<String> missing = new LinkedHashSet<>();
        for (ClaimView c : claims.values()) {
            if (!byId.containsKey(c.subjectId())) {
                missing.add(c.subjectId());
            }
            if (c.objectId() != null && !byId.containsKey(c.objectId())) {
                missing.add(c.objectId());
            }
        }
        if (!missing.isEmpty()) {
            for (EntityView e : reader.entitiesByIds(scope, missing).values()) {
                if ("ACTIVE".equals(e.status()) && byId.size() < MAX_ENTITIES + 20) {
                    Found f = new Found(e, "k" + (++k), false);
                    byId.put(e.id(), f);
                }
            }
        }

        CandidateSet.Builder set = CandidateSet.builder();
        for (Found f : byId.values()) {
            f.names.add(f.entity.name());
            f.names.addAll(reader.aliasesOf(scope, f.entity.id()));
        }
        List<MentionMatches> ranked = rank(lookups);
        // the prompt keeps the best-matching candidates when it is over budget (ADR-031)
        Map<String, Double> best = new LinkedHashMap<>();
        for (MentionMatches mm : ranked) {
            for (Match m : mm.ranked()) {
                best.merge(m.entityId(), m.score(), Math::max);
            }
        }
        List<PromptEntity> promptEntities = new ArrayList<>();
        for (Found f : byId.values()) {
            EntityView e = f.entity;
            List<String> aliasNorms = f.names.subList(1, f.names.size());
            Set<String> norms = new HashSet<>(aliasNorms);
            if (e.normalizedName() != null) {
                norms.add(e.normalizedName());
            }
            set.entity(new EntityCandidate(f.handle, e.id(), e.type(), e.name(), e.normalizedName(), e.canonicalKey(),
                    norms, e.descriptionLocked(), f.mentionTypes));
            List<String> shown = new ArrayList<>();
            for (var a : reader.aliases(scope, e.id())) {
                String d = a.display() == null ? a.normalized() : a.display();
                if (!d.equalsIgnoreCase(e.name()) && shown.size() < 5) {
                    shown.add(d);
                }
            }
            Double rank = f.self ? Double.valueOf(2.0) : best.get(e.id());
            promptEntities.add(new PromptEntity(f.handle, e.type(), f.self ? speakerLabel : e.name(), shown,
                    e.description(), rank));
        }

        List<PromptClaim> promptClaims = new ArrayList<>();
        int c = 0;
        for (ClaimView v : claims.values()) {
            if (promptClaims.size() >= MAX_CLAIMS) {
                break;
            }
            Found subject = byId.get(v.subjectId());
            Found object = v.objectId() == null ? null : byId.get(v.objectId());
            if (subject == null || (v.objectId() != null && object == null)) {
                continue;
            }
            String handle = "c" + (++c);
            ClaimKind kind = v.relation() ? ClaimKind.RELATION : ClaimKind.ATTRIBUTE;
            set.claim(new ClaimCandidate(handle, v.id(), v.subjectId(), v.predicate(), kind, v.objectId(),
                    v.valueType() == null ? null : ValueType.valueOf(v.valueType()), v.valueNorm(),
                    Polarity.valueOf(v.polarity()), Origin.valueOf(v.origin())));
            promptClaims.add(new PromptClaim(handle, subject.handle, v.predicate(), object == null ? null : object.handle,
                    v.value(), v.polarity(), v.statement(), v.origin()));
        }
        return new Candidates(set.build(), promptEntities, promptClaims, ranked);
    }

    /** Ranks each mention's candidates: exact alias first, then by name similarity (ADR-030). */
    private static List<MentionMatches> rank(List<Lookup> lookups) {
        List<MentionMatches> out = new ArrayList<>();
        for (Lookup l : lookups) {
            List<String> names = new ArrayList<>();
            names.add(l.mention().name());
            names.addAll(l.mention().aliases());
            List<Match> ranked = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Map.Entry<Found, String> e : l.found()) {
                Found f = e.getKey();
                if (!seen.add(f.entity.id())) {
                    continue;
                }
                double similarity = 0;
                for (String n : names) {
                    similarity = Math.max(similarity, NameMatch.best(n, f.names));
                }
                double score = switch (e.getValue()) {
                    case "ALIAS" -> 1.0;
                    case "COMPACT" -> Math.max(COMPACT_SCORE, similarity);
                    default -> similarity;
                };
                ranked.add(new Match(f.handle, f.entity.id(), e.getValue(), score));
            }
            ranked.sort(java.util.Comparator.comparingDouble(Match::score).reversed());
            out.add(new MentionMatches(l.mention().name(), l.type(), List.copyOf(ranked)));
        }
        return out;
    }

    private static void hit(Map<String, String> hits, Map<String, EntityView> views, EntityView e, String via) {
        hits.putIfAbsent(e.id(), via);
        views.putIfAbsent(e.id(), e);
    }

    private record Lookup(Extraction.Mention mention, String type, List<Map.Entry<Found, String>> found) {
    }

    private Optional<EntityView> active(BrainScope scope, EntityView e) {
        if ("ACTIVE".equals(e.status())) {
            return Optional.of(e);
        }
        if ("MERGED".equals(e.status())) {
            return reader.resolveEntity(scope, e.id()).filter(x -> "ACTIVE".equals(x.status()));
        }
        return Optional.empty();
    }

    private static final class Found {
        final EntityView entity;
        String handle;
        final boolean self;
        final Set<String> mentionTypes = new LinkedHashSet<>();
        final List<String> names = new ArrayList<>();

        Found(EntityView entity, String handle, boolean self) {
            this.entity = entity;
            this.handle = handle;
            this.self = self;
        }
    }
}
