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
import io.graphspindle.graph.internal.BrainStatements;
import io.graphspindle.graph.internal.ClaimStore;
import io.graphspindle.graph.internal.ConflictStore;
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

    private final GraphTransactions transactions;
    private final EntityStore entities;
    private final AliasStore aliases;
    private final ClaimStore claims;
    private final ProjectionStore projections;
    private final SourceRefStore sources;
    private final ConflictStore conflicts;

    public GraphStore(GraphTransactions transactions, EntityStore entities, AliasStore aliases, ClaimStore claims,
            ProjectionStore projections, SourceRefStore sources, ConflictStore conflicts) {
        this.transactions = transactions;
        this.entities = entities;
        this.aliases = aliases;
        this.claims = claims;
        this.projections = projections;
        this.sources = sources;
        this.conflicts = conflicts;
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
            mentions.forEach((entityId, surface) -> entities.mention(tx, entityId, ingestionId,
                    surface.isEmpty() ? null : surface));
            return complete();
        }

        private void checkBrainAndIngestion() {
            var brain = tx.run(BrainStatements.FIND).list();
            if (brain.isEmpty()) {
                throw GraphSpindleException.brainNotFound(tx.brainId());
            }
            if (!"ACTIVE".equals(brain.get(0).get("b").get("status").asString())) {
                throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "brain is being deleted");
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
                        k -> mention(entities.require(tx, k).id(), null));
                case ResolvedEntity.New x -> {
                    String id = idsByKey.get(x.canonicalKey());
                    if (id == null) {
                        throw new IllegalStateException("ref used before its create_entity: " + x.ref());
                    }
                    yield id;
                }
            };
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
            entities.updateDescription(tx, entityId, c.description());
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
                ClaimView reinforced = claims.addEvidence(tx, same.get().id(), ingestionId, rc.evidence(),
                        confidence(rc));
                cite(reinforced.id(), rc);
                projections.sync(tx, req.vocabulary(), reinforced);
                outcome(Outcome.CLAIM_REINFORCED, subjectId, reinforced.id(), null);
                return reinforced;
            }
            Optional<ClaimView> existing = contradicted(rc, subjectId, objectId, keys);
            if (existing.isEmpty()) {
                return createActive(rc, subjectId, objectId, keys);
            }
            ClaimView old = existing.get();
            if (req.policy() == ConflictPolicy.LATEST_WINS && !Origin.MANUAL.name().equals(old.origin())
                    && req.principal().equals(old.createdByPrincipal())) {
                return supersedeWith(old, rc, "latest wins: contradiction from the same principal");
            }
            return disputed(rc, subjectId, objectId, old, "CONTRADICTION",
                    "Contradicts an active claim: \"" + old.statement() + "\" vs \"" + rc.statement() + "\"");
        }

        /** The ACTIVE claim occupying the same ONE slot, or the same triple with the opposite polarity. */
        private Optional<ClaimView> contradicted(ResolvedClaim rc, String subjectId, String objectId,
                ResolvedClaim.Keys keys) {
            if (keys.slotKey().isPresent()) {
                Optional<ClaimView> slot = claims.findBySlotKey(tx, keys.slotKey().get());
                if (slot.isPresent()) {
                    return slot;
                }
            }
            Polarity opposite = rc.polarity() == Polarity.POSITIVE ? Polarity.NEGATIVE : Polarity.POSITIVE;
            String oppositeKey = ClaimKeys.activeKey(tx.brainId(), subjectId, rc.predicate(),
                    ClaimKeys.objectPart(rc.kind(), objectId, rc.valueType(), rc.valueNorm()), opposite);
            return claims.findByActiveKey(tx, oppositeKey);
        }

        private ClaimView createActive(ResolvedClaim rc, String subjectId, String objectId, ResolvedClaim.Keys keys) {
            ClaimView c = create(rc, subjectId, objectId, "ACTIVE", keys);
            projections.sync(tx, req.vocabulary(), c);
            createdActive.add(c);
            outcome(Outcome.CLAIM_CREATED, subjectId, c.id(), null);
            return c;
        }

        private ClaimView disputed(ResolvedClaim rc, String subjectId, String objectId, ClaimView existing,
                String kind, String description) {
            ClaimView incoming = create(rc, subjectId, objectId, "DISPUTED", null);
            String conflictId = conflicts.create(tx, kind, description, ingestionId);
            if (existing != null) {
                conflicts.involveClaim(tx, conflictId, existing.id(), "EXISTING");
            }
            conflicts.involveClaim(tx, conflictId, incoming.id(), "INCOMING");
            outcome(Outcome.CLAIM_DISPUTED, subjectId, incoming.id(), conflictId);
            return incoming;
        }

        private ClaimView create(ResolvedClaim rc, String subjectId, String objectId, String status,
                ResolvedClaim.Keys keys) {
            if (rc.kind() == ClaimKind.RELATION) {
                io.graphspindle.graph.internal.Tokens.relationType(req.vocabulary(), rc.predicate());
            } else {
                io.graphspindle.graph.internal.Tokens.attributeProperty(req.vocabulary(), rc.predicate());
            }
            boolean evidenced = rc.evidence() != null && !rc.evidence().isBlank();
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
            boolean explicitAllowed = c.explicitCorrection() && req.policy() != ConflictPolicy.ALWAYS_FLAG
                    && (req.allowLlmSupersession() || req.origin() == Origin.MANUAL);
            boolean latestWins = req.policy() == ConflictPolicy.LATEST_WINS
                    && req.principal().equals(target.createdByPrincipal());
            if (explicitAllowed || latestWins) {
                supersedeWith(target, replacement, c.reason());
                return;
            }
            String objectId = replacement.kind() == ClaimKind.RELATION ? id(replacement.object()) : null;
            disputed(replacement, target.subjectId(), objectId, target, "CONTRADICTION",
                    "Correction not applied automatically: " + c.reason());
        }

        /** Supersedes {@code old} (keys and projection removed), then asserts the replacement. */
        private ClaimView supersedeWith(ClaimView old, ResolvedClaim replacement, String reason) {
            ClaimView superseded = claims.leaveActive(tx, old.id(), "SUPERSEDED", Normalizer.truncate(reason, 300),
                    null, ingestionId);
            projections.sync(tx, req.vocabulary(), superseded);
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
                outcome(Outcome.SKIPPED, target.subjectId(), target.id(), null);
                return;
            }
            ClaimView retracted = claims.leaveActive(tx, target.id(), "RETRACTED", c.reason(), c.evidence(),
                    ingestionId);
            projections.sync(tx, req.vocabulary(), retracted);
            outcome(Outcome.CLAIM_RETRACTED, target.subjectId(), target.id(), null);
        }

        private void flagConflict(FlagConflictCommand c) {
            ClaimView existing = claims.require(tx, c.claimId());
            ResolvedClaim in = c.incoming();
            String subjectId = id(in.subject());
            String objectId = in.kind() == ClaimKind.RELATION ? id(in.object()) : null;
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

        /**
         * SCHEMA §10.3: an ACTIVE {@code REPLACES} claim between decisions makes the engine set the
         * replaced decision's {@code status} to the engine-only value {@code SUPERSEDED} with a SYSTEM
         * claim. A MANUAL status claim is never overridden (PROTECTED_OVERRIDE conflict instead).
         */
        private void applyConsequences() {
            Set<String> replaced = new LinkedHashSet<>();
            for (ClaimView c : createdActive) {
                if ("REPLACES".equals(c.predicate()) && c.relation() && "POSITIVE".equals(c.polarity())) {
                    replaced.add(c.objectId());
                }
            }
            for (String decisionId : replaced) {
                EntityView decision = entities.findById(tx, decisionId).orElseThrow();
                Optional<AttributePredicateDef> status = req.vocabulary().attribute("status", decision.type());
                if (status.isEmpty() || !status.get().enumValues().contains("SUPERSEDED")) {
                    continue;
                }
                String slot = ClaimKeys.slotKey(tx.brainId(), decisionId, "status", status.get().cardinality(),
                        Polarity.POSITIVE).orElse(null);
                Optional<ClaimView> current = slot == null ? Optional.empty() : claims.findBySlotKey(tx, slot);
                if (current.isPresent() && "SUPERSEDED".equals(current.get().valueNorm())) {
                    continue;
                }
                if (current.isPresent() && Origin.MANUAL.name().equals(current.get().origin())) {
                    String conflictId = conflicts.create(tx, "PROTECTED_OVERRIDE",
                            "a replacing decision would set the MANUAL status of this decision to SUPERSEDED",
                            ingestionId);
                    conflicts.involveClaim(tx, conflictId, current.get().id(), "EXISTING");
                    outcome(Outcome.PROTECTED_OVERRIDE, decisionId, current.get().id(), conflictId);
                    continue;
                }
                String statement = Normalizer.truncate("Decision \"" + decision.name()
                        + "\" was superseded by a replacing decision.", 500);
                Map<String, Object> props = P.of("id", Ids.newId(), "kind", "ATTRIBUTE", "predicate", "status",
                        "subjectId", decisionId, "valueType", status.get().valueType().name(), "value", "SUPERSEDED",
                        "valueNorm", "SUPERSEDED", "polarity", "POSITIVE", "statement", statement, "status", "ACTIVE",
                        "confidence", 1.0, "origin", Origin.SYSTEM.name(), "cardinality",
                        status.get().cardinality().name(), "createdByIngestionId", ingestionId,
                        "createdByPrincipal", req.principal(), "evidenceCount", 0);
                current.ifPresent(old -> {
                    ClaimView s = claims.leaveActive(tx, old.id(), "SUPERSEDED", "replaced by another decision", null,
                            ingestionId);
                    projections.sync(tx, req.vocabulary(), s);
                    outcome(Outcome.CLAIM_SUPERSEDED, decisionId, old.id(), null);
                });
                props.put("activeKey", ClaimKeys.activeKey(tx.brainId(), decisionId, "status",
                        ClaimKeys.attributeObject(status.get().valueType(), "SUPERSEDED"), Polarity.POSITIVE));
                props.put("slotKey", slot);
                ClaimView created = claims.create(tx, props);
                projections.sync(tx, req.vocabulary(), created);
                current.ifPresent(old -> claims.supersedes(tx, created.id(), old.id(), "replaced by another decision"));
                outcome(Outcome.CONSEQUENCE_APPLIED, decisionId, created.id(), null);
            }
        }
    }
}
