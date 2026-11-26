package io.graphspindle.ingestion.pipeline;

import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ClaimKind;
import io.graphspindle.common.Normalizer;
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
 * extracted mention: exact alias lookup of {@code norm(name)} and of each alias, then fulltext top 5,
 * filtered to ACTIVE entities of a compatible type. For each candidate up to 10 ACTIVE claims (newest
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

    private final GraphReader reader;

    public CandidateStage(GraphReader reader) {
        this.reader = reader;
    }

    /**
     * @param speaker the entity "I" refers to, or null when the named speaker does not exist yet
     * @param speakerLabel how the speaker is shown to the model
     */
    public record Candidates(CandidateSet set, List<PromptEntity> entities, List<PromptClaim> claims) {
    }

    public Candidates find(BrainScope scope, BrainVocabulary vocabulary, Extraction extraction, EntityView speaker,
            String speakerLabel) {
        Map<String, Found> byId = new LinkedHashMap<>();
        if (speaker != null) {
            byId.put(speaker.id(), new Found(speaker, OperationValidator.SPEAKER_HANDLE, true));
        }
        for (Extraction.Mention m : extraction.mentions()) {
            String type = m.type() != null && vocabulary.isEnabledType(m.type()) ? m.type() : null;
            List<EntityView> hits = new ArrayList<>();
            Set<String> names = new LinkedHashSet<>();
            names.add(m.name());
            names.addAll(m.aliases());
            for (String n : names) {
                String norm = Normalizer.norm(n);
                if (!norm.isEmpty() && !norm.startsWith("@")) {
                    hits.addAll(reader.entitiesByAlias(scope, norm));
                }
            }
            for (ScoredEntity s : reader.fulltextEntities(scope, m.name(), FULLTEXT_K)) {
                hits.add(s.entity());
            }
            for (EntityView e : hits) {
                Optional<EntityView> live = active(scope, e);
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
        List<PromptEntity> promptEntities = new ArrayList<>();
        for (Found f : byId.values()) {
            EntityView e = f.entity;
            List<String> aliasNorms = reader.aliasesOf(scope, e.id());
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
            promptEntities.add(new PromptEntity(f.handle, e.type(), f.self ? speakerLabel : e.name(), shown,
                    e.description()));
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
        return new Candidates(set.build(), promptEntities, promptClaims);
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

        Found(EntityView entity, String handle, boolean self) {
            this.entity = entity;
            this.handle = handle;
            this.self = self;
        }
    }
}
