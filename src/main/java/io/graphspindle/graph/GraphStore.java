package io.graphspindle.graph;

import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ClaimKind;
import io.graphspindle.common.ConflictPolicy;
import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Ids;
import io.graphspindle.common.Normalizer;
import io.graphspindle.common.Origin;
import io.graphspindle.common.Polarity;
import io.graphspindle.graph.ApplyResult.OpOutcome;
import io.graphspindle.graph.ApplyResult.Outcome;
import io.graphspindle.graph.internal.AliasStore;
import io.graphspindle.graph.internal.BrainLock;
import io.graphspindle.graph.internal.ClaimStore;
import io.graphspindle.graph.internal.ConflictStore;
import io.graphspindle.graph.internal.ConsequenceRules;
import io.graphspindle.graph.internal.EntityStore;
import io.graphspindle.graph.internal.GraphTransactions;
import io.graphspindle.graph.internal.IngestionStatements;
import io.graphspindle.graph.internal.P;
import io.graphspindle.graph.internal.ProjectionStore;
import io.graphspindle.graph.internal.ScopedTx;
import io.graphspindle.graph.internal.SourceRefStore;
import io.graphspindle.ingestion.validation.AliasSpec;
import io.graphspindle.ingestion.validation.PlanCommand;
import io.graphspindle.ingestion.validation.PlanCommand.AddAliasCommand;
import io.graphspindle.ingestion.validation.PlanCommand.AssertClaimCommand;
import io.graphspindle.ingestion.validation.PlanCommand.CreateEntityCommand;
import io.graphspindle.ingestion.validation.PlanCommand.FlagConflictCommand;
import io.graphspindle.ingestion.validation.PlanCommand.LinkSourceCommand;
import io.graphspindle.ingestion.validation.PlanCommand.ProposeMergeCommand;
import io.graphspindle.ingestion.validation.PlanCommand.ProtectedOverrideCommand;
import io.graphspindle.ingestion.validation.PlanCommand.RetractClaimCommand;
import io.graphspindle.ingestion.validation.PlanCommand.SupersedeClaimCommand;
import io.graphspindle.ingestion.validation.PlanCommand.UpdateEntityCommand;
import io.graphspindle.ingestion.validation.ResolvedClaim;
import io.graphspindle.ingestion.validation.ResolvedEntity;
import io.graphspindle.vocabulary.AttributePredicateDef;
import io.graphspindle.vocabulary.ClaimKeys;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Applies a {@link io.graphspindle.ingestion.validation.ValidatedPlan} in <strong>one</strong> write
 * transaction (ARCHITECTURE §3 stage 5, SCHEMA §9): entities, aliases, source refs, claims,
 * supersessions and retractions, conflicts, consequence rules, mention edges, then the Ingestion
 * record. Claims are the source of truth; projection edges and {@code attr_*} properties are kept in
 * sync in the same transaction. Any exception rolls everything back. A uniqueness-constraint race with
 * a concurrent writer is retried once; MERGE by canonicalKey and the claim keys then resolve to the
 * winner's nodes.
 */
@Component
public class GraphStore {

    private static final Logger log = LoggerFactory.getLogger(GraphStore.class);
    /** Decision predicates the model never rewrites (ADR-028 §6, ADR-031). */
    static final java.util.Set<String> IMMUTABLE_DECISION_PREDICATES = java.util.Set.of("CHOOSES", "REJECTS");

    private final GraphTransactions transactions;
    private final EntityStore entities;
    private final AliasStore aliases;
    private final ClaimStore claims;
    private final ProjectionStore projections;
    private final SourceRefStore sources;
    private final ConflictStore conflicts;
    private final ConsequenceRules consequences;

    public GraphStore(GraphTransactions transactions, EntityStore entities, AliasStore aliases, ClaimStore claims,
            ProjectionStore projections, SourceRefStore sources, ConflictStore conflicts) {
        this.transactions = transactions;
        this.entities = entities;
        this.aliases = aliases;
        this.claims = claims;
        this.projections = projections;
        this.sources = sources;
        this.conflicts = conflicts;
        this.consequences = new ConsequenceRules(entities, claims, projections, conflicts);
    }

    public ApplyResult apply(BrainScope scope, ApplyRequest request) {
        try {
            return transactions.write(scope, tx -> new Execution(tx, request, 1).run());
        } catch (RuntimeException e) {
            if (!GraphTransactions.isConstraintViolation(e)) {
                throw e;
            }
            log.debug("uniqueness race while applying ingestion {}; retrying once", request.ingestionId());
            return transactions.write(scope, tx -> new Execution(tx, request, 2).run());
        }
    }

    /** Execution order of command kinds (ARCHITECTURE §3 stage 5); stable within a phase. */
    static int phase(PlanCommand c) {
        return switch (c) {
            case CreateEntityCommand x -> 0;
            case AddAliasCommand x -> 1;
            case UpdateEntityCommand x -> 1;
            case LinkSourceCommand x -> 2;
            case AssertClaimCommand x -> 3;
            case SupersedeClaimCommand x -> 4;
            case RetractClaimCommand x -> 4;
            case FlagConflictCommand x -> 5;
            case ProposeMergeCommand x -> 5;
            case ProtectedOverrideCommand x -> 5;
        };
    }

    /** One attempt; created inside the transaction callback so a driver retry starts from scratch. */
    private final class Execution {

        private final ScopedTx tx;
        private final ApplyRequest req;
        private final int attempt;
        private final String ingestionId;
        private final List<OpOutcome> outcomes = new ArrayList<>();
        private final Map<String, String> idsByKey = new HashMap<>();
        private final Map<String, String> refs = new LinkedHashMap<>();
        private final Map<String, String> resolvedExisting = new HashMap<>();
        private final Map<String, String> mentions = new LinkedHashMap<>();
        private final List<String> sourceIds = new ArrayList<>();
        private final List<ClaimView> createdActive = new ArrayList<>();
        private int index;

        Execution(ScopedTx tx, ApplyRequest req, int attempt) {
            this.tx = tx;
            this.req = req;
            this.attempt = attempt;
            this.ingestionId = req.ingestionId();
        }

        ApplyResult run() {
            checkBrainAndIngestion();
            for (SourceInput s : req.sources()) {
                sourceIds.add(sources.upsert(tx, s, ingestionId));
            }
            List<PlanCommand> ordered = new ArrayList<>(req.plan().commands());
            ordered.sort(Comparator.comparingInt(GraphStore::phase));
            for (PlanCommand c : ordered) {
                index = c.opIndex();
                switch (c) {
                    case CreateEntityCommand x -> createEntity(x);
                    case AddAliasCommand x -> addAlias(id(x.target()), x.alias());
                    case UpdateEntityCommand x -> updateEntity(x);
                    case LinkSourceCommand x -> linkSource(x);
                    case AssertClaimCommand x -> assertClaim(x.claim());
                    case SupersedeClaimCommand x -> supersede(x);
                    case RetractClaimCommand x -> retract(x);
                    case FlagConflictCommand x -> flagConflict(x);
                    case ProposeMergeCommand x -> proposeMerge(x);
                    case ProtectedOverrideCommand x -> protectedOverride(x);
                }
            }
            index = -1;
            applyConsequences();
            if (ingestionId == null) {
                return complete();
            }
            mentions.forEach((entityId, surface) -> entities.mention(tx, entityId, ingestionId,
                    surface.isEmpty() ? null : surface));
            return complete();
        }

        private boolean manual() {
            return req.origin() == Origin.MANUAL;
        }

        /**
         * A Decision's CHOOSES/REJECTS is history (ADR-028 §6): the model never rewrites it, not even
         * through LATEST_WINS; a change is a new Decision that REPLACES the old one (ADR-031).
         */
        private boolean immutableForModel(ClaimView claim) {
            return !manual() && IMMUTABLE_DECISION_PREDICATES.contains(claim.predicate())
                    && entities.findById(tx, claim.subjectId()).map(e -> "Decision".equals(e.type())).orElse(false);
        }

        /** Brain write lock first (serializes with brain deletion), then the RECEIVED ingestion, if any. */
        private void checkBrainAndIngestion() {
            BrainLock.lockActive(tx);
            if (ingestionId == null) {
                return;
            }
            var rows = tx.run(IngestionStatements.LOCK_RECEIVED, P.of("id", ingestionId)).list();
            if (rows.isEmpty()) {
                throw GraphSpindleException.notFound("ingestion", ingestionId);
            }
            if (!"RECEIVED".equals(rows.get(0).get("status").asString())) {
                throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "ingestion is not in status RECEIVED");
            }
        }

        private ApplyResult complete() {
            int applied = (int) outcomes.stream().filter(o -> o.opIndex() >= 0)
                    .filter(o -> o.outcome() != Outcome.SKIPPED && o.outcome() != Outcome.PROTECTED_OVERRIDE)
                    .map(OpOutcome::opIndex).distinct().count();
            String status = applied > 0 ? "APPLIED" : "NO_CHANGES";
            if (ingestionId == null) {
                return new ApplyResult(null, status, applied, refs, outcomes, attempt);
            }
            IngestionCompletion c = req.completion();
            tx.run(IngestionStatements.COMPLETE, P.of("id", ingestionId, "props", P.of("status", status, "summary",
                    c.summary(), "opsProposed", c.opsProposed(), "opsApplied", applied, "opsRejected", c.opsRejected(),
                    "report", c.report(), "llmProvider", c.llmProvider(), "llmModel", c.llmModel(), "promptVersion",
                    c.promptVersion()))).consume();
            return new ApplyResult(ingestionId, status, applied, refs, outcomes, attempt);
        }

        private void outcome(Outcome o, String entityId, String claimId, String conflictId) {
            outcomes.add(new OpOutcome(index, o, entityId, claimId, conflictId));
        }

        // ------------------------------------------------------------------ entities

        private String id(ResolvedEntity e) {
            return switch (e) {
                case ResolvedEntity.Existing x -> resolvedExisting.computeIfAbsent(x.entityId(),
                        k -> mention(writable(entities.require(tx, k)).id(), null));
                case ResolvedEntity.New x -> {
                    String id = idsByKey.get(x.canonicalKey());
                    if (id == null) {
                        throw new IllegalStateException("ref used before its create_entity: " + x.ref());
                    }
                    yield id;
                }
            };
        }

        /** Manual writes refuse archived entities (they are hidden, not deleted; un-archive first). */
        private EntityView writable(EntityView e) {
            if (manual() && !"ACTIVE".equals(e.status())) {
                throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "entity is " + e.status() + ": " + e.id());
            }
            return e;
        }

        private String mention(String entityId, String surfaceForm) {
            if (surfaceForm != null || !mentions.containsKey(entityId)) {
                mentions.merge(entityId, surfaceForm == null ? "" : surfaceForm,
                        (old, now) -> old.isEmpty() ? now : old);
            }
            return entityId;
        }

        private void createEntity(CreateEntityCommand c) {
            ResolvedEntity.New e = c.entity();
            String existing = idsByKey.get(e.canonicalKey());
            EntityStore.Merged m = existing != null ? new EntityStore.Merged(existing, e.type(), false)
                    : entities.mergeByKey(tx, req.vocabulary(), e.type(), e.canonicalKey(), c.name(), c.normalizedName(),
                            c.description(), req.origin().name(), ingestionId);
            idsByKey.put(e.canonicalKey(), m.id());
            refs.put(e.ref(), m.id());
            mention(m.id(), Normalizer.truncate(c.name(), 200));
            if (manual() && m.created() && c.description() != null) {
                entities.updateDescription(tx, m.id(), c.description(), true);
            }
            outcome(m.created() ? Outcome.ENTITY_CREATED : Outcome.ENTITY_MATCHED, m.id(), null, null);
            addAlias(m.id(), new AliasSpec(c.name(), c.normalizedName()));
            c.aliases().forEach(a -> addAlias(m.id(), a));
        }

        private void addAlias(String entityId, AliasSpec alias) {
            if (aliases.upsert(tx, entityId, alias.normalized(), alias.display(), req.origin().name())) {
                outcome(Outcome.ALIAS_ADDED, entityId, null, null);
            }
        }

        private void updateEntity(UpdateEntityCommand c) {
            String entityId = id(c.target());
            EntityView e = entities.findById(tx, entityId).orElseThrow();
            if (e.descriptionLocked() && req.origin() != Origin.MANUAL) {
                String conflictId = conflicts.create(tx, "PROTECTED_OVERRIDE",
                        "update_entity on an entity with a locked description", ingestionId);
                conflicts.involveEntity(tx, conflictId, entityId, "EXISTING");
                outcome(Outcome.PROTECTED_OVERRIDE, entityId, null, conflictId);
                return;
            }
            // a MANUAL description edit locks the description against LLM updates (SCHEMA §5.4)
            entities.updateDescription(tx, entityId, c.description(), manual() || e.descriptionLocked());
            outcome(Outcome.ENTITY_UPDATED, entityId, null, null);
        }

        private void linkSource(LinkSourceCommand c) {
            String entityId = id(c.target());
            sources.describes(tx, sourceId(c.sourceIndex()), entityId);
            outcome(Outcome.SOURCE_LINKED, entityId, null, null);
        }

        private String sourceId(int sourceIndex) {
            if (sourceIndex < 0 || sourceIndex >= sourceIds.size()) {
                throw new IllegalArgumentException("source index out of range: " + sourceIndex);
            }
            return sourceIds.get(sourceIndex);
        }

        // ------------------------------------------------------------------ claims

        /** SCHEMA §9.2: reinforce, create, or route a contradiction by policy. Returns the claim written. */
        private ClaimView assertClaim(ResolvedClaim rc) {
            String subjectId = id(rc.subject());
            String objectId = rc.kind() == ClaimKind.RELATION ? id(rc.object()) : null;
            if (subjectId.equals(objectId)) {
                outcome(Outcome.SKIPPED, subjectId, null, null);
                return null;
            }
            ResolvedClaim.Keys keys = rc.keys(tx.brainId(), subjectId, objectId);
            Optional<ClaimView> same = claims.findByActiveKey(tx, keys.activeKey());
            if (same.isPresent()) {
                ClaimView reinforced = reinforce(same.get(), rc);
                cite(reinforced.id(), rc);
                projections.sync(tx, reinforced);
                outcome(Outcome.CLAIM_REINFORCED, subjectId, reinforced.id(), null);
                return reinforced;
            }
            Optional<ClaimView> existing = contradicted(rc, subjectId, objectId, keys);
            if (existing.isEmpty()) {
                return createActive(rc, subjectId, objectId, keys);
            }
            ClaimView old = existing.get();
            if (req.policy() == ConflictPolicy.LATEST_WINS && !Origin.MANUAL.name().equals(old.origin())
                    && req.principal().equals(old.createdByPrincipal()) && !immutableForModel(old)) {
                return supersedeWith(old, rc, "latest wins: contradiction from the same principal");
            }
            return disputed(rc, subjectId, objectId, old, "CONTRADICTION",
                    "Contradicts an active claim: \"" + old.statement() + "\" vs \"" + rc.statement() + "\"");
        }

        /**
         * Adds this write's evidence to an existing claim. An ingestion adds an EVIDENCED_BY edge; a
         * MANUAL assertion of the same fact confirms it: origin MANUAL and confidence 1.0, so the
         * claim is protected from LLM corrections from then on (ADR-023).
         */
        private ClaimView reinforce(ClaimView claim, ResolvedClaim rc) {
            if (ingestionId != null) {
                return claims.addEvidence(tx, claim.id(), ingestionId, rc.evidence(), confidence(rc));
            }
            return manual() ? claims.confirmManual(tx, claim.id(), req.principal()) : claim;
        }

        /** The ACTIVE claim occupying the same ONE slot, or the same triple with the opposite polarity. */
        private Optional<ClaimView> contradicted(ResolvedClaim rc, String subjectId, String objectId,
                ResolvedClaim.Keys keys) {
            return contradicted(rc, subjectId, objectId, keys, null);
        }

        /** As above, ignoring {@code ignoreId} (the claim that is about to be superseded). */
        private Optional<ClaimView> contradicted(ResolvedClaim rc, String subjectId, String objectId,
                ResolvedClaim.Keys keys, String ignoreId) {
            if (keys.slotKey().isPresent()) {
                Optional<ClaimView> slot = claims.findBySlotKey(tx, keys.slotKey().get());
                if (slot.isPresent() && !slot.get().id().equals(ignoreId)) {
                    return slot;
                }
            }
            Polarity opposite = rc.polarity() == Polarity.POSITIVE ? Polarity.NEGATIVE : Polarity.POSITIVE;
            String oppositeKey = ClaimKeys.activeKey(tx.brainId(), subjectId, rc.predicate(),
                    ClaimKeys.objectPart(rc.kind(), objectId, rc.valueType(), rc.valueNorm()), opposite);
            return claims.findByActiveKey(tx, oppositeKey).filter(c -> !c.id().equals(ignoreId));
        }

        private ClaimView createActive(ResolvedClaim rc, String subjectId, String objectId, ResolvedClaim.Keys keys) {
            ClaimView c = create(rc, subjectId, objectId, "ACTIVE", keys);
            projections.sync(tx, c);
            createdActive.add(c);
            outcome(Outcome.CLAIM_CREATED, subjectId, c.id(), null);
            return c;
        }

        private ClaimView disputed(ResolvedClaim rc, String subjectId, String objectId, ClaimView existing,
                String kind, String description) {
            Optional<ClaimView> repeated = repeatedDispute(rc, subjectId, objectId, existing, kind);
            if (repeated.isPresent()) {
                return repeated.get();
            }
            ClaimView incoming = create(rc, subjectId, objectId, "DISPUTED", null);
            String conflictId = conflicts.create(tx, kind, description, ingestionId);
            if (existing != null) {
                conflicts.involveClaim(tx, conflictId, existing.id(), "EXISTING");
            }
            conflicts.involveClaim(tx, conflictId, incoming.id(), "INCOMING");
            outcome(Outcome.CLAIM_DISPUTED, subjectId, incoming.id(), conflictId);
            return incoming;
        }

        /**
         * A repeated identical contradiction (same triple and polarity against the same existing claim,
         * conflict still OPEN) reinforces the DISPUTED claim of that conflict instead of creating
         * another DISPUTED claim and conflict.
         */
        private Optional<ClaimView> repeatedDispute(ResolvedClaim rc, String subjectId, String objectId,
                ClaimView existing, String kind) {
            if (existing == null) {
                return Optional.empty();
            }
            Optional<ConflictStore.Dispute> d = conflicts.openDispute(tx, kind, existing.id(), subjectId,
                    rc.predicate(), rc.polarity().name(), objectId,
                    rc.valueType() == null ? null : rc.valueType().name(), rc.valueNorm());
            if (d.isEmpty()) {
                return Optional.empty();
            }
            ClaimView claim = ingestionId == null ? d.get().claim()
                    : claims.addEvidence(tx, d.get().claim().id(), ingestionId, rc.evidence(), confidence(rc));
            cite(claim.id(), rc);
            outcome(Outcome.DISPUTE_REINFORCED, subjectId, claim.id(), d.get().conflictId());
            return Optional.of(claim);
        }

        private ClaimView create(ResolvedClaim rc, String subjectId, String objectId, String status,
                ResolvedClaim.Keys keys) {
            if (rc.kind() == ClaimKind.RELATION) {
                io.graphspindle.graph.internal.Tokens.relationType(req.vocabulary(), rc.predicate());
            } else {
                io.graphspindle.graph.internal.Tokens.attributeProperty(req.vocabulary(), rc.predicate());
            }
            boolean evidenced = ingestionId != null && rc.evidence() != null && !rc.evidence().isBlank();
            Map<String, Object> props = P.of("id", Ids.newId(), "kind", rc.kind().name(), "predicate", rc.predicate(),
                    "subjectId", subjectId, "objectId", objectId,
                    "valueType", rc.valueType() == null ? null : rc.valueType().name(), "value", rc.value(),
                    "valueNorm", rc.valueNorm(), "polarity", rc.polarity().name(), "statement", rc.statement(),
                    "status", status, "confidence", confidence(rc), "origin", req.origin().name(),
                    "cardinality", rc.cardinality().name(),
                    "validFrom", rc.validFrom() == null ? null : rc.validFrom().value(),
                    "validFromSort", rc.validFrom() == null ? null : rc.validFrom().sortKey(),
                    "validTo", rc.validTo() == null ? null : rc.validTo().value(),
                    "validToSort", rc.validTo() == null ? null : rc.validTo().sortKey(),
                    "createdByIngestionId", ingestionId, "createdByPrincipal", req.principal(),
                    "activeKey", keys == null ? null : keys.activeKey(),
                    "slotKey", keys == null ? null : keys.slotKey().orElse(null), "evidenceCount", 0);
            ClaimView c = claims.create(tx, props);
            if (evidenced) {
                c = claims.addEvidence(tx, c.id(), ingestionId, rc.evidence(), confidence(rc));
            }
            cite(c.id(), rc);
            return c;
        }

        private double confidence(ResolvedClaim rc) {
            return req.origin() == Origin.MANUAL ? 1.0 : rc.confidence();
        }

        private void cite(String claimId, ResolvedClaim rc) {
            for (int s : rc.sources()) {
                claims.cite(tx, claimId, sourceId(s));
            }
        }

        private void supersede(SupersedeClaimCommand c) {
            ClaimView target = claims.require(tx, c.claimId());
            ResolvedClaim replacement = c.replacement();
            if (!target.active()) {
                if (manual()) {
                    throw new GraphSpindleException(ErrorCode.CONFLICT_STATE,
                            "only an ACTIVE claim can be superseded; claim is " + target.status());
                }
                // stale handle (changed since stage 2): treat the replacement as an ordinary assertion
                assertClaim(replacement);
                return;
            }
            if (Origin.MANUAL.name().equals(target.origin()) && req.origin() != Origin.MANUAL) {
                protectedClaim(target, replacement, "supersede_claim on a MANUAL claim: " + c.reason());
                return;
            }
            boolean sameSubject = target.subjectId().equals(id(replacement.subject()))
                    && target.predicate().equals(replacement.predicate());
            if (!sameSubject) {
                throw new IllegalArgumentException("replacement must keep subject and predicate");
            }
            // a user's correction is always applied (SCHEMA §9.6); LLM corrections depend on policy
            boolean explicitAllowed = manual() || c.explicitCorrection()
                    && req.policy() != ConflictPolicy.ALWAYS_FLAG && req.allowLlmSupersession();
            boolean latestWins = req.policy() == ConflictPolicy.LATEST_WINS
                    && req.principal().equals(target.createdByPrincipal());
            if ((explicitAllowed || latestWins) && !immutableForModel(target)) {
                supersedeWith(target, replacement, c.reason());
                return;
            }
            String objectId = replacement.kind() == ClaimKind.RELATION ? id(replacement.object()) : null;
            disputed(replacement, target.subjectId(), objectId, target, "CONTRADICTION",
                    "Correction not applied automatically: " + c.reason());
        }

        /**
         * Supersedes {@code old} (keys and projection removed), then asserts the replacement. If the
         * replacement would itself be disputed by another ACTIVE claim (the opposite polarity of the
         * same triple, or another claim in its slot), nothing is superseded: the slot must never end
         * up empty. The replacement is stored DISPUTED against that blocking claim and {@code old}
         * stays ACTIVE (ADR-022).
         */
        private ClaimView supersedeWith(ClaimView old, ResolvedClaim replacement, String reason) {
            String subjectId = id(replacement.subject());
            String objectId = replacement.kind() == ClaimKind.RELATION ? id(replacement.object()) : null;
            if (!subjectId.equals(objectId)) {
                ResolvedClaim.Keys keys = replacement.keys(tx.brainId(), subjectId, objectId);
                boolean identicalExists = claims.findByActiveKey(tx, keys.activeKey())
                        .filter(c -> !c.id().equals(old.id())).isPresent();
                Optional<ClaimView> blocker = identicalExists ? Optional.empty()
                        : contradicted(replacement, subjectId, objectId, keys, old.id());
                if (blocker.isPresent()) {
                    return disputed(replacement, subjectId, objectId, blocker.get(), "CONTRADICTION",
                            "Correction not applied: the replacement contradicts another active claim: \""
                                    + blocker.get().statement() + "\"");
                }
            }
            ClaimView superseded = claims.leaveActive(tx, old.id(), "SUPERSEDED", Normalizer.truncate(reason, 300),
                    null, ingestionId);
            projections.sync(tx, superseded);
            if (replacement.polarity() == Polarity.NEGATIVE && replacement.validFrom() != null
                    && superseded.validTo() == null) {
                claims.setValidTo(tx, old.id(), replacement.validFrom().value(), replacement.validFrom().sortKey());
            }
            outcome(Outcome.CLAIM_SUPERSEDED, superseded.subjectId(), old.id(), null);
            ClaimView now = assertClaim(replacement);
            if (now != null) {
                claims.supersedes(tx, now.id(), old.id(), Normalizer.truncate(reason, 300));
            }
            return now;
        }

        private void retract(RetractClaimCommand c) {
            ClaimView target = claims.require(tx, c.claimId());
            if (Origin.MANUAL.name().equals(target.origin()) && req.origin() != Origin.MANUAL) {
                protectedClaim(target, null, "retract_claim on a MANUAL claim: " + c.reason());
                return;
            }
            if (!target.active() && !"DISPUTED".equals(target.status())) {
                if (manual()) {
                    throw new GraphSpindleException(ErrorCode.CONFLICT_STATE,
                            "only an ACTIVE or DISPUTED claim can be retracted; claim is " + target.status());
                }
                outcome(Outcome.SKIPPED, target.subjectId(), target.id(), null);
                return;
            }
            ClaimView retracted = claims.leaveActive(tx, target.id(), "RETRACTED", c.reason(), c.evidence(),
                    ingestionId);
            projections.sync(tx, retracted);
            outcome(Outcome.CLAIM_RETRACTED, target.subjectId(), target.id(), null);
        }

        private void flagConflict(FlagConflictCommand c) {
            ClaimView existing = claims.require(tx, c.claimId());
            ResolvedClaim in = c.incoming();
            String subjectId = id(in.subject());
            String objectId = in.kind() == ClaimKind.RELATION ? id(in.object()) : null;
            // LATEST_WINS (SCHEMA §9.4): a contradiction of the principal's own non-MANUAL claim supersedes it,
            // also when the engine downgraded the model's correction to a flag (ADR-030)
            if (req.policy() == ConflictPolicy.LATEST_WINS && existing.active()
                    && !Origin.MANUAL.name().equals(existing.origin())
                    && req.principal().equals(existing.createdByPrincipal())
                    && existing.subjectId().equals(subjectId) && existing.predicate().equals(in.predicate())
                    && !immutableForModel(existing)) {
                supersedeWith(existing, in, "latest wins: contradiction from the same principal");
                return;
            }
            disputed(in, subjectId, objectId, existing, "CONTRADICTION", c.description());
        }

        private void protectedOverride(ProtectedOverrideCommand c) {
            if (c.targetClaimId() != null) {
                protectedClaim(claims.require(tx, c.targetClaimId()), c.incoming(), c.description());
                return;
            }
            String entityId = entities.require(tx, c.targetEntityId()).id();
            String conflictId = conflicts.create(tx, "PROTECTED_OVERRIDE", c.description(), ingestionId);
            conflicts.involveEntity(tx, conflictId, entityId, "EXISTING");
            outcome(Outcome.PROTECTED_OVERRIDE, entityId, null, conflictId);
        }

        /** The protected claim is untouched; an incoming replacement (if any) is stored DISPUTED. */
        private void protectedClaim(ClaimView target, ResolvedClaim incoming, String description) {
            if (incoming != null) {
                String subjectId = id(incoming.subject());
                String objectId = incoming.kind() == ClaimKind.RELATION ? id(incoming.object()) : null;
                if (repeatedDispute(incoming, subjectId, objectId, target, "PROTECTED_OVERRIDE").isPresent()) {
                    return;
                }
            }
            String conflictId = conflicts.create(tx, "PROTECTED_OVERRIDE", description, ingestionId);
            conflicts.involveClaim(tx, conflictId, target.id(), "EXISTING");
            String incomingId = null;
            if (incoming != null) {
                String subjectId = id(incoming.subject());
                String objectId = incoming.kind() == ClaimKind.RELATION ? id(incoming.object()) : null;
                incomingId = create(incoming, subjectId, objectId, "DISPUTED", null).id();
                conflicts.involveClaim(tx, conflictId, incomingId, "INCOMING");
            }
            outcome(Outcome.PROTECTED_OVERRIDE, target.subjectId(), incomingId, conflictId);
        }

        private void proposeMerge(ProposeMergeCommand c) {
            String a = id(c.a());
            String b = id(c.b());
            if (a.equals(b) || conflicts.openDuplicate(tx, a, b).isPresent()
                    || conflicts.openDuplicate(tx, b, a).isPresent()) {
                outcome(Outcome.SKIPPED, a, null, null);
                return;
            }
            String conflictId = conflicts.create(tx, "DUPLICATE_CANDIDATE", c.reason(), ingestionId);
            conflicts.involveEntity(tx, conflictId, a, "CANDIDATE_A");
            conflicts.involveEntity(tx, conflictId, b, "CANDIDATE_B");
            outcome(Outcome.CONFLICT_OPENED, a, null, conflictId);
        }

        // ------------------------------------------------------------------ consequence rules

        /** SCHEMA §10.3 consequence rules over the claims this plan made ACTIVE ({@link ConsequenceRules}). */
        private void applyConsequences() {
            consequences.apply(tx, req.vocabulary(), createdActive, ingestionId, req.principal(), this::outcome);
        }
    }
}
