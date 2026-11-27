package io.graphspindle.knowledge;

import io.graphspindle.brain.BrainConfig;
import io.graphspindle.brain.BrainService;
import io.graphspindle.common.Actor;
import io.graphspindle.common.Permission;
import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ClaimKind;
import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Normalizer;
import io.graphspindle.common.Page;
import io.graphspindle.common.Paging;
import io.graphspindle.common.PartialDate;
import io.graphspindle.common.Polarity;
import io.graphspindle.graph.ApplyRequest;
import io.graphspindle.graph.ApplyResult;
import io.graphspindle.graph.ApplyResult.OpOutcome;
import io.graphspindle.graph.ApplyResult.Outcome;
import io.graphspindle.graph.ClaimView;
import io.graphspindle.graph.EntityView;
import io.graphspindle.graph.GraphReader;
import io.graphspindle.graph.GraphStore;
import io.graphspindle.graph.KnowledgeStore;
import io.graphspindle.ingestion.validation.AliasSpec;
import io.graphspindle.ingestion.validation.PlanCommand;
import io.graphspindle.ingestion.validation.ResolvedClaim;
import io.graphspindle.ingestion.validation.ResolvedEntity;
import io.graphspindle.ingestion.validation.SecretScanner;
import io.graphspindle.ingestion.validation.ValidatedPlan;
import io.graphspindle.knowledge.KnowledgeModel.AliasInfo;
import io.graphspindle.knowledge.KnowledgeModel.ClaimDetail;
import io.graphspindle.knowledge.KnowledgeModel.ClaimInfo;
import io.graphspindle.knowledge.KnowledgeModel.ClaimWrite;
import io.graphspindle.knowledge.KnowledgeModel.EntityChanges;
import io.graphspindle.knowledge.KnowledgeModel.EntityInfo;
import io.graphspindle.knowledge.KnowledgeModel.EntityWrite;
import io.graphspindle.knowledge.KnowledgeModel.HistoryEntry;
import io.graphspindle.knowledge.KnowledgeModel.MergeRequest;
import io.graphspindle.knowledge.KnowledgeModel.MergeResult;
import io.graphspindle.knowledge.KnowledgeModel.NewAlias;
import io.graphspindle.knowledge.KnowledgeModel.NewClaim;
import io.graphspindle.knowledge.KnowledgeModel.NewEntity;
import io.graphspindle.knowledge.KnowledgeModel.Replacement;
import io.graphspindle.knowledge.KnowledgeModel.Retraction;
import io.graphspindle.knowledge.KnowledgeModel.SourceDetail;
import io.graphspindle.knowledge.KnowledgeModel.SourceInfo;
import io.graphspindle.knowledge.KnowledgeModel.Supersession;
import io.graphspindle.vocabulary.AttributePredicateDef;
import io.graphspindle.vocabulary.AttributeValues;
import io.graphspindle.vocabulary.BrainVocabulary;
import io.graphspindle.vocabulary.RelationPredicateDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Manual knowledge management (SCHEMA §9.5, §9.6), no LLM: entities, aliases, claims, corrections
 * and merges with {@code Origin.MANUAL}, plus claim and source reads. This is the single entry point
 * for REST (M5) and MCP tools (M8). Every method takes the calling {@link Actor} and the brain id
 * from the request path or tool argument (never from content); authorization ({@code BrainAccessGuard})
 * is added here in M9.
 */
@Service
public class KnowledgeService {

    static final int MAX_NAME = 200;
    static final int MAX_DESCRIPTION = 1000;
    static final int MAX_ALIASES = 20;
    static final int MAX_STATEMENT = 500;
    static final int MAX_REASON = 300;
    static final int MAX_DISTINCT = 50;
    static final Set<String> ENTITY_STATUSES = Set.of("ACTIVE", "MERGED", "ARCHIVED");
    static final Set<String> CLAIM_STATUSES = Set.of("ACTIVE", "DISPUTED", "SUPERSEDED", "RETRACTED");

    private final BrainService brains;
    private final GraphStore graph;
    private final KnowledgeStore store;
    private final GraphReader reader;
    private final KnowledgeViews views;
    private final SecretScanner secrets = new SecretScanner();

    public KnowledgeService(BrainService brains, GraphStore graph, KnowledgeStore store, GraphReader reader,
            KnowledgeViews views) {
        this.brains = brains;
        this.graph = graph;
        this.store = store;
        this.reader = reader;
        this.views = views;
    }

    // ------------------------------------------------------------------ entities

    public Page<EntityInfo> listEntities(Actor actor, String brainId, String type, String status, String cursor,
            Integer limit) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.READ);
        if (type != null && !type.matches("[A-Za-z][A-Za-z0-9]{0,39}")) {
            throw GraphSpindleException.validation("invalid type");
        }
        String st = status == null ? "ACTIVE" : status.toUpperCase(Locale.ROOT);
        if (!ENTITY_STATUSES.contains(st)) {
            throw GraphSpindleException.validation("status must be one of " + ENTITY_STATUSES);
        }
        Page<EntityView> page = reader.listEntities(scope, type, st, Paging.cursor(cursor),
                Paging.limit(limit, Paging.DEFAULT_LIMIT, Paging.MAX_LIMIT));
        return new Page<>(page.items().stream().map(KnowledgeViews::entity).toList(), page.next());
    }

    /** The entity itself (not followed through MERGED_INTO); 404 when it is not in this brain. */
    public EntityInfo getEntity(Actor actor, String brainId, String entityId) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.READ);
        return KnowledgeViews.entity(entity(scope, entityId));
    }

    /**
     * Creates a MANUAL entity. If an entity with the same type and normalized name exists it is
     * returned with {@code created = false} (aliases are still added), unless {@code distinct = true},
     * which creates a separate entity with key suffix {@code ~2}, {@code ~3} ... (SCHEMA §7.3).
     */
    public EntityWrite createEntity(Actor actor, String brainId, NewEntity request) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.WRITE);
        BrainVocabulary vocabulary = brains.vocabulary(scope);
        if (request == null || request.type() == null || !vocabulary.isEnabledType(request.type())) {
            throw GraphSpindleException.validation("type must be an entity type enabled in the brain vocabulary");
        }
        String name = name(request.name());
        String norm = Normalizer.normUntruncated(name);
        String description = optionalText("description", request.description(), MAX_DESCRIPTION);
        List<AliasSpec> aliases = new ArrayList<>();
        List<String> raw = request.aliases() == null ? List.of() : request.aliases();
        if (raw.size() > MAX_ALIASES) {
            throw new GraphSpindleException(ErrorCode.LIMIT_EXCEEDED, "at most " + MAX_ALIASES + " aliases");
        }
        for (String a : raw) {
            String display = name(a);
            aliases.add(new AliasSpec(Normalizer.truncate(display, MAX_NAME), Normalizer.normUntruncated(display)));
        }
        String key = Normalizer.canonicalKey(request.type(), norm);
        if (Boolean.TRUE.equals(request.distinct())) {
            key = freeKey(scope, key);
        }
        PlanCommand create = new PlanCommand.CreateEntityCommand(0, new ResolvedEntity.New("e", request.type(), key),
                name, norm, description, aliases);
        ApplyResult r = applyManual(actor, scope, vocabulary, create);
        String id = r.refs().get("e");
        boolean created = r.outcomes(Outcome.ENTITY_CREATED).stream().anyMatch(o -> id.equals(o.entityId()));
        return new EntityWrite(KnowledgeViews.entity(entity(scope, id)), created, aliases(scope, id));
    }

    private String freeKey(BrainScope scope, String key) {
        if (reader.entityByKey(scope, key).isEmpty()) {
            return key;
        }
        for (int n = 2; n <= MAX_DISTINCT; n++) {
            String candidate = key + "~" + n;
            if (reader.entityByKey(scope, candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new GraphSpindleException(ErrorCode.LIMIT_EXCEEDED, "too many distinct entities with this name");
    }

    /**
     * Edits name, description or the description lock (MANUAL). A new description locks it against
     * LLM updates (SCHEMA §5.4) unless {@code descriptionLocked = false} is given. A rename keeps the
     * old name as an alias; renaming onto another entity's name is {@code CONFLICT_STATE} (merge instead).
     */
    public EntityInfo updateEntity(Actor actor, String brainId, String entityId, EntityChanges changes) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.WRITE);
        EntityView e = entity(scope, entityId);
        if (changes == null || changes.name() == null && changes.description() == null
                && changes.descriptionLocked() == null) {
            throw GraphSpindleException.validation("nothing to change");
        }
        String name = null;
        String norm = null;
        String key = null;
        if (changes.name() != null) {
            name = name(changes.name());
            norm = Normalizer.normUntruncated(name);
            key = Normalizer.canonicalKey(e.type(), norm);
        }
        String description = changes.description() == null ? null
                : requiredText("description", changes.description(), MAX_DESCRIPTION);
        EntityView updated = store.updateEntity(scope, e.id(), new KnowledgeStore.EntityEdit(name, norm, key,
                description, changes.descriptionLocked()), actor.id());
        return KnowledgeViews.entity(updated);
    }

    /** Soft delete (ARCHIVED): hidden from search and neighbourhoods, still readable by id. */
    public EntityInfo archiveEntity(Actor actor, String brainId, String entityId) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.WRITE);
        return KnowledgeViews.entity(store.archiveEntity(scope, entity(scope, entityId).id(), actor.id()));
    }

    public EntityWrite addAlias(Actor actor, String brainId, String entityId, NewAlias request) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.WRITE);
        EntityView e = resolved(scope, entityId);
        String display = name(request == null ? null : request.alias());
        PlanCommand add = new PlanCommand.AddAliasCommand(0, new ResolvedEntity.Existing(e.id(), e.type(), null),
                new AliasSpec(display, Normalizer.normUntruncated(display)));
        ApplyResult r = applyManual(actor, scope, brains.vocabulary(scope), add);
        return new EntityWrite(KnowledgeViews.entity(entity(scope, e.id())),
                !r.outcomes(Outcome.ALIAS_ADDED).isEmpty(), aliases(scope, e.id()));
    }

    public List<AliasInfo> aliases(Actor actor, String brainId, String entityId) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.READ);
        return aliases(scope, entity(scope, entityId).id());
    }

    private List<AliasInfo> aliases(BrainScope scope, String entityId) {
        return reader.aliases(scope, entityId).stream().map(KnowledgeViews::alias).toList();
    }

    /** Merges {@code loserId} into {@code survivorId} (SCHEMA §9.5); the loser redirects to the survivor. */
    public MergeResult mergeEntities(Actor actor, String brainId, MergeRequest request) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.WRITE);
        if (request == null || request.survivorId() == null || request.loserId() == null) {
            throw GraphSpindleException.validation("survivorId and loserId are required");
        }
        String reason = optionalText("reason", request.reason(), MAX_REASON);
        String survivor = entity(scope, request.survivorId()).id();
        String loser = entity(scope, request.loserId()).id();
        KnowledgeStore.MergeOutcome m = store.merge(scope, brains.vocabulary(scope), survivor, loser, actor.id(),
                reason);
        return mergeResult(scope, m);
    }

    /** Shared with the conflict service (MERGE resolution). */
    public MergeResult mergeResult(BrainScope scope, KnowledgeStore.MergeOutcome m) {
        return new MergeResult(KnowledgeViews.entity(entity(scope, m.survivorId())), m.loserId(), m.claimsRepointed(),
                m.claimsReinforced(), m.claimsDisputed(), m.claimsRetracted(), m.aliasesMoved(), m.conflictIds(),
                m.resolvedDuplicateConflictIds());
    }

    // ------------------------------------------------------------------ claims

    /** Keyset page of claims; every filter is optional ({@code status} null means any status). */
    public Page<ClaimInfo> listClaims(Actor actor, String brainId, String subjectId, String objectId,
            String predicate, String status, String cursor, Integer limit) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.READ);
        if (predicate != null && !predicate.matches("[A-Za-z][A-Za-z0-9_]{0,39}")) {
            throw GraphSpindleException.validation("invalid predicate");
        }
        String st = status == null ? null : status.toUpperCase(Locale.ROOT);
        if (st != null && !CLAIM_STATUSES.contains(st)) {
            throw GraphSpindleException.validation("status must be one of " + CLAIM_STATUSES);
        }
        Page<ClaimView> page = reader.listClaims(scope, subjectId, objectId, predicate, st, Paging.cursor(cursor),
                Paging.limit(limit, Paging.DEFAULT_LIMIT, Paging.MAX_LIMIT));
        return new Page<>(views.claims(scope, page.items()), page.next());
    }

    /**
     * Asserts a MANUAL claim (confidence 1.0) through the same write semantics as ingestion (SCHEMA
     * §9.2): an identical ACTIVE claim is confirmed (it becomes MANUAL, ADR-023), a contradiction is
     * stored DISPUTED with a CONTRADICTION conflict, otherwise the claim is ACTIVE and projected.
     */
    public ClaimWrite assertClaim(Actor actor, String brainId, NewClaim request) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.WRITE);
        BrainVocabulary vocabulary = brains.vocabulary(scope);
        if (request == null || request.subjectId() == null) {
            throw GraphSpindleException.validation("subjectId is required");
        }
        EntityView subject = resolved(scope, request.subjectId());
        ResolvedClaim claim = resolveClaim(scope, vocabulary, subject, request.predicate(), request.objectId(),
                request.value(), request.polarity(), request.statement(), request.validFrom(), request.validTo());
        ApplyResult r = applyManual(actor, scope, vocabulary, new PlanCommand.AssertClaimCommand(0, claim));
        return claimWrite(scope, r, null);
    }

    public ClaimDetail getClaim(Actor actor, String brainId, String claimId) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.READ);
        GraphReader.ClaimProvenance p = Paging.plausibleId(claimId) ? reader.provenance(scope, claimId, 20).orElse(null)
                : null;
        if (p == null) {
            throw GraphSpindleException.notFound("claim", claimId);
        }
        return new ClaimDetail(views.claim(scope, p.claim()), p.evidence().stream().map(KnowledgeViews::evidence)
                .toList(), p.sources().stream().map(KnowledgeViews::source).toList(), p.links().supersedes(),
                p.links().supersededBy(), p.conflictIds(), true);
    }

    /** The SUPERSEDES chain the claim belongs to, oldest first (SCHEMA §10.3 history). */
    public List<HistoryEntry> claimHistory(Actor actor, String brainId, String claimId) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.READ);
        List<GraphReader.ClaimLinked> chain = Paging.plausibleId(claimId) ? reader.claimHistory(scope, claimId)
                : List.of();
        if (chain.isEmpty()) {
            throw GraphSpindleException.notFound("claim", claimId);
        }
        List<ClaimInfo> infos = views.claims(scope, chain.stream().map(GraphReader.ClaimLinked::claim).toList());
        List<HistoryEntry> out = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            var links = chain.get(i).links();
            out.add(new HistoryEntry(infos.get(i), links.supersedes(), links.supersededBy(), links.reason()));
        }
        return out;
    }

    /**
     * A user's correction (SCHEMA §9.6): the ACTIVE claim is SUPERSEDED by the replacement (same
     * subject and predicate), linked by SUPERSEDES. Always allowed for MANUAL writes, whatever the
     * conflict policy. If another ACTIVE claim contradicts the replacement, nothing is superseded and
     * the replacement is DISPUTED with a conflict (ADR-022).
     */
    public ClaimWrite supersedeClaim(Actor actor, String brainId, String claimId, Supersession request) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.WRITE);
        BrainVocabulary vocabulary = brains.vocabulary(scope);
        ClaimView target = claim(scope, claimId);
        if (request == null || request.replacement() == null) {
            throw GraphSpindleException.validation("replacement is required");
        }
        String reason = requiredText("reason", request.reason(), MAX_REASON);
        Replacement rep = request.replacement();
        EntityView subject = resolved(scope, target.subjectId());
        ResolvedClaim replacement = resolveClaim(scope, vocabulary, subject, target.predicate(), rep.objectId(),
                rep.value(), rep.polarity(), rep.statement(), rep.validFrom(), rep.validTo());
        ApplyResult r = applyManual(actor, scope, vocabulary,
                new PlanCommand.SupersedeClaimCommand(0, target.id(), true, reason, replacement));
        String superseded = r.outcomes(Outcome.CLAIM_SUPERSEDED).stream().map(OpOutcome::claimId)
                .filter(target.id()::equals).findFirst().orElse(null);
        return claimWrite(scope, r, superseded);
    }

    /** Retracts an ACTIVE or DISPUTED claim (keys and projection removed); history is kept. */
    public ClaimWrite retractClaim(Actor actor, String brainId, String claimId, Retraction request) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.WRITE);
        ClaimView target = claim(scope, claimId);
        String reason = requiredText("reason", request == null ? null : request.reason(), MAX_REASON);
        applyManual(actor, scope, brains.vocabulary(scope),
                new PlanCommand.RetractClaimCommand(0, target.id(), reason, null));
        return new ClaimWrite(views.claim(scope, claim(scope, target.id())), "RETRACTED", null, null);
    }

    // ------------------------------------------------------------------ sources

    public Page<SourceInfo> listSources(Actor actor, String brainId, String cursor, Integer limit) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.READ);
        var page = reader.listSources(scope, Paging.cursor(cursor),
                Paging.limit(limit, Paging.DEFAULT_LIMIT, Paging.MAX_LIMIT));
        return new Page<>(page.items().stream().map(KnowledgeViews::source).toList(), page.next());
    }

    public SourceDetail getSource(Actor actor, String brainId, String sourceId) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.READ);
        var use = Paging.plausibleId(sourceId) ? reader.source(scope, sourceId, 100).orElse(null) : null;
        if (use == null) {
            throw GraphSpindleException.notFound("source", sourceId);
        }
        List<EntityInfo> describes = reader.entitiesByIds(scope, use.describesEntityIds()).values().stream()
                .map(KnowledgeViews::entity).toList();
        return new SourceDetail(KnowledgeViews.source(use.source()), describes, use.citedByClaimIds(), true);
    }

    // ------------------------------------------------------------------ helpers

    private ApplyResult applyManual(Actor actor, BrainScope scope, BrainVocabulary vocabulary, PlanCommand command) {
        BrainConfig config = brains.config(scope);
        ValidatedPlan plan = new ValidatedPlan("manual", List.of(command), List.of(), List.of(), Map.of());
        return graph.apply(scope, ApplyRequest.manual(actor.id(), plan, vocabulary, config.conflictPolicy()));
    }

    private ClaimWrite claimWrite(BrainScope scope, ApplyResult r, String superseded) {
        for (OpOutcome o : r.outcomes()) {
            String outcome = switch (o.outcome()) {
                case CLAIM_CREATED -> "CREATED";
                case CLAIM_REINFORCED -> "REINFORCED";
                case CLAIM_DISPUTED -> "DISPUTED";
                case DISPUTE_REINFORCED -> "DISPUTE_REINFORCED";
                default -> null;
            };
            if (outcome != null && o.claimId() != null) {
                return new ClaimWrite(views.claim(scope, claim(scope, o.claimId())), outcome, o.conflictId(),
                        superseded);
            }
        }
        if (superseded != null) {
            return new ClaimWrite(views.claim(scope, claim(scope, superseded)), "SUPERSEDED", null, superseded);
        }
        throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "the claim was not written (self-loop after merge)");
    }

    /** Validates a manual claim against the brain vocabulary and resolves it for the write path. */
    private ResolvedClaim resolveClaim(BrainScope scope, BrainVocabulary vocabulary, EntityView subject,
            String predicate, String objectId, String value, String polarityText, String statementText,
            String validFromText, String validToText) {
        if (predicate == null || predicate.isBlank()) {
            throw GraphSpindleException.validation("predicate is required");
        }
        Polarity polarity;
        try {
            polarity = polarityText == null ? Polarity.POSITIVE : Polarity.valueOf(polarityText.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw GraphSpindleException.validation("polarity must be POSITIVE or NEGATIVE");
        }
        PartialDate validFrom = date("validFrom", validFromText);
        PartialDate validTo = date("validTo", validToText);
        if (validFrom != null && validTo != null && validFrom.sortKey().compareTo(validTo.sortKey()) > 0) {
            throw GraphSpindleException.validation("validFrom must not be after validTo");
        }
        ResolvedEntity.Existing s = new ResolvedEntity.Existing(subject.id(), subject.type(), null);
        Optional<RelationPredicateDef> relation = vocabulary.relation(predicate);
        if (relation.isPresent()) {
            if (objectId == null || value != null) {
                throw GraphSpindleException.validation(predicate + " is a relation predicate: give objectId, not value");
            }
            EntityView object = resolved(scope, objectId);
            if (object.id().equals(subject.id())) {
                throw GraphSpindleException.validation("subject and object must differ (self-loop)");
            }
            if (!vocabulary.allowsRelation(predicate, subject.type(), object.type())) {
                throw GraphSpindleException.validation(predicate + " does not allow " + subject.type() + " -> "
                        + object.type());
            }
            String statement = statement(statementText, subject.name() + " " + words(predicate) + " " + object.name(),
                    polarity);
            return new ResolvedClaim(s, predicate, ClaimKind.RELATION, relation.get().cardinality(),
                    new ResolvedEntity.Existing(object.id(), object.type(), null), null, null, null, polarity, statement,
                    null, 1.0, validFrom, validTo, List.of(), null, null);
        }
        Optional<AttributePredicateDef> attribute = vocabulary.attribute(predicate, subject.type());
        if (attribute.isEmpty()) {
            throw GraphSpindleException.validation(vocabulary.hasAttributeName(predicate)
                    ? predicate + " does not apply to " + subject.type()
                    : "predicate is not in the brain vocabulary: " + Normalizer.truncate(predicate, 40));
        }
        if (objectId != null || value == null) {
            throw GraphSpindleException.validation(predicate + " is an attribute predicate: give value, not objectId");
        }
        AttributeValues.Checked checked = AttributeValues.check(attribute.get(), value);
        if (!checked.ok()) {
            throw GraphSpindleException.validation(checked.error());
        }
        noSecret("value", checked.value());
        String statement = statement(statementText, subject.name() + " " + words(predicate) + " is " + checked.value(),
                polarity);
        return new ResolvedClaim(s, predicate, ClaimKind.ATTRIBUTE, attribute.get().cardinality(), null,
                attribute.get().valueType(), checked.value(), checked.valueNorm(), polarity, statement, null, 1.0,
                validFrom, validTo, List.of(), null, null);
    }

    private String statement(String given, String generated, Polarity polarity) {
        if (given != null) {
            return requiredText("statement", given, MAX_STATEMENT);
        }
        String s = (polarity == Polarity.NEGATIVE ? "Not: " : "") + generated + ".";
        return Normalizer.truncate(Normalizer.normalizeWhitespace(s), MAX_STATEMENT);
    }

    private static String words(String predicate) {
        return predicate.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static PartialDate date(String field, String raw) {
        if (raw == null) {
            return null;
        }
        return PartialDate.parse(raw).orElseThrow(() -> GraphSpindleException.validation(field
                + " must be YYYY, YYYY-MM or YYYY-MM-DD and a real date"));
    }

    /** A name or alias: non-empty after normalization, at most 200 code points normalized, no {@code @}, no secret. */
    private String name(String raw) {
        if (raw == null || raw.isBlank()) {
            throw GraphSpindleException.validation("name is required");
        }
        String n = Normalizer.normalizeWhitespace(raw);
        String norm = Normalizer.normUntruncated(n);
        if (norm.isEmpty() || Normalizer.codePointLength(n) > MAX_NAME
                || Normalizer.codePointLength(norm) > Normalizer.MAX_NAME_CODE_POINTS) {
            throw GraphSpindleException.validation("name must have 1 to " + MAX_NAME + " characters");
        }
        if (norm.startsWith("@")) {
            throw GraphSpindleException.validation("names starting with @ are reserved");
        }
        noSecret("name", n);
        return n;
    }

    private String optionalText(String field, String value, int max) {
        return value == null ? null : requiredText(field, value, max);
    }

    private String requiredText(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            throw GraphSpindleException.validation(field + " is required");
        }
        String v = value.strip();
        if (Normalizer.codePointLength(v) > max) {
            throw GraphSpindleException.validation(field + " must be at most " + max + " characters");
        }
        noSecret(field, v);
        return v;
    }

    private void noSecret(String field, String value) {
        if (secrets.containsSecret(value)) {
            throw GraphSpindleException.validation(field + " looks like a secret (credentials are never stored)");
        }
    }

    private EntityView entity(BrainScope scope, String id) {
        return (Paging.plausibleId(id) ? reader.entity(scope, id) : Optional.<EntityView>empty())
                .orElseThrow(() -> GraphSpindleException.notFound("entity", id));
    }

    /** The entity or the survivor it was merged into; must be ACTIVE to be written to. */
    private EntityView resolved(BrainScope scope, String id) {
        EntityView e = (Paging.plausibleId(id) ? reader.resolveEntity(scope, id) : Optional.<EntityView>empty())
                .orElseThrow(() -> GraphSpindleException.notFound("entity", id));
        if (!"ACTIVE".equals(e.status())) {
            throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "entity is " + e.status() + ": " + e.id());
        }
        return e;
    }

    private ClaimView claim(BrainScope scope, String id) {
        return (Paging.plausibleId(id) ? reader.claim(scope, id) : Optional.<ClaimView>empty())
                .orElseThrow(() -> GraphSpindleException.notFound("claim", id));
    }
}
