package io.graphspindle.graph;

import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ClaimKind;
import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Normalizer;
import io.graphspindle.common.PartialDate;
import io.graphspindle.common.Polarity;
import io.graphspindle.graph.internal.AliasStore;
import io.graphspindle.graph.internal.BrainLock;
import io.graphspindle.graph.internal.ClaimStore;
import io.graphspindle.graph.internal.ConflictStore;
import io.graphspindle.graph.internal.EntityStore;
import io.graphspindle.graph.internal.GraphTransactions;
import io.graphspindle.graph.internal.KnowledgeStatements;
import io.graphspindle.graph.internal.P;
import io.graphspindle.graph.internal.ProjectionStore;
import io.graphspindle.graph.internal.Rows;
import io.graphspindle.graph.internal.ScopedTx;
import io.graphspindle.vocabulary.Cardinality;
import io.graphspindle.vocabulary.ClaimKeys;
import io.graphspindle.vocabulary.ValueType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Manual knowledge writes that are not plan commands (M5, SCHEMA §9.4–§9.6): entity edits and
 * archiving, the full entity merge, and conflict resolution. Each method is one write transaction
 * that first takes the Brain lock ({@code BrainLock}), so it serializes with ingestion applies and
 * with brain deletion. Claim creation, supersession and retraction go through
 * {@link GraphStore#apply} with {@link ApplyRequest#manual}.
 */
@Component
public class KnowledgeStore {

    private static final String SELF_NORM = "@self";

    private final GraphTransactions transactions;
    private final EntityStore entities;
    private final AliasStore aliases;
    private final ClaimStore claims;
    private final ProjectionStore projections;
    private final ConflictStore conflicts;

    public KnowledgeStore(GraphTransactions transactions, EntityStore entities, AliasStore aliases, ClaimStore claims,
            ProjectionStore projections, ConflictStore conflicts) {
        this.transactions = transactions;
        this.entities = entities;
        this.aliases = aliases;
        this.claims = claims;
        this.projections = projections;
        this.conflicts = conflicts;
    }

    // ------------------------------------------------------------------ entity edits

    /**
     * A manual entity edit. Null fields are unchanged.
     *
     * @param name new display name; with {@code normalizedName} and {@code canonicalKey} when the key changes
     * @param description new description; setting it locks it against LLM updates
     * @param descriptionLocked explicit lock state (applied after {@code description})
     */
    public record EntityEdit(String name, String normalizedName, String canonicalKey, String description,
            Boolean descriptionLocked) {
    }

    public EntityView updateEntity(BrainScope scope, String entityId, EntityEdit edit, String principal) {
        try {
            return transactions.write(scope, tx -> {
                BrainLock.lockActive(tx);
                EntityView e = lockEntity(tx, entityId);
                requireActive(e);
                boolean self = SELF_NORM.equals(e.normalizedName());
                String norm = null;
                String key = null;
                if (edit.name() != null && !self && !edit.canonicalKey().equals(e.canonicalKey())) {
                    Optional<EntityView> taken = entities.findByKey(tx, edit.canonicalKey());
                    if (taken.isPresent()) {
                        throw new GraphSpindleException(ErrorCode.CONFLICT_STATE,
                                "another entity already has this name and type; merge them instead",
                                Map.of("entityId", taken.get().id()), null);
                    }
                    norm = edit.normalizedName();
                    key = edit.canonicalKey();
                }
                boolean setDescription = edit.description() != null;
                Boolean locked = edit.descriptionLocked() != null ? edit.descriptionLocked()
                        : setDescription ? Boolean.TRUE : null;
                EntityView updated = Rows.entity(tx.run(KnowledgeStatements.UPDATE_ENTITY, P.of("id", e.id(), "name",
                        edit.name(), "normalizedName", norm, "canonicalKey", key, "setDescription", setDescription,
                        "description", edit.description(), "locked", locked)).single().get("n"));
                if (edit.name() != null && !self) {
                    aliases.upsert(tx, e.id(), edit.normalizedName(), Normalizer.truncate(edit.name(), 200), "MANUAL");
                }
                return updated;
            });
        } catch (RuntimeException ex) {
            if (GraphTransactions.isConstraintViolation(ex)) {
                throw new GraphSpindleException(ErrorCode.CONFLICT_STATE,
                        "another entity already has this name and type; merge them instead", Map.of(), ex);
            }
            throw ex;
        }
    }

    /** Soft delete: ACTIVE becomes ARCHIVED (idempotent). The self entity cannot be archived. */
    public EntityView archiveEntity(BrainScope scope, String entityId, String principal) {
        return transactions.write(scope, tx -> {
            BrainLock.lockActive(tx);
            EntityView e = lockEntity(tx, entityId);
            if ("ARCHIVED".equals(e.status())) {
                return e;
            }
            requireActive(e);
            if (SELF_NORM.equals(e.normalizedName())) {
                throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "the self entity cannot be archived");
            }
            return Rows.entity(tx.run(KnowledgeStatements.ARCHIVE_ENTITY, P.of("id", e.id(), "by", principal)).single()
                    .get("n"));
        });
    }

    // ------------------------------------------------------------------ merge (SCHEMA §9.5)

    /** What a merge did. {@code conflictIds} are CONTRADICTION conflicts opened for slot collisions. */
    public record MergeOutcome(String survivorId, String loserId, int claimsRepointed, int claimsReinforced,
            int claimsDisputed, int claimsRetracted, int aliasesMoved, List<String> conflictIds,
            List<String> resolvedDuplicateConflictIds) {

        public MergeOutcome {
            conflictIds = List.copyOf(conflictIds);
            resolvedDuplicateConflictIds = List.copyOf(resolvedDuplicateConflictIds);
        }
    }

    /**
     * Merges {@code loserId} into {@code survivorId} in one transaction: every claim of the loser is
     * re-pointed and re-keyed (identical claims reinforce the survivor's claim and the loser's copy is
     * SUPERSEDED; a slot or polarity collision makes the loser's claim DISPUTED with a CONTRADICTION
     * conflict; a claim that becomes a self-loop is RETRACTED), aliases, mentions and DESCRIBES links
     * move, projections are rebuilt, open duplicate candidates between the two are resolved, and the
     * loser is tombstoned with {@code MERGED_INTO}. Types must be compatible (SCHEMA §6.2).
     */
    public MergeOutcome merge(BrainScope scope, io.graphspindle.vocabulary.BrainVocabulary vocabulary, String survivorId,
            String loserId, String principal, String note) {
        if (survivorId.equals(loserId)) {
            throw GraphSpindleException.validation("an entity cannot be merged into itself");
        }
        return transactions.write(scope, tx -> {
            BrainLock.lockActive(tx);
            return new Merge(tx, principal, note).run(survivorId, loserId, vocabulary);
        });
    }

    private final class Merge {

        private final ScopedTx tx;
        private final String principal;
        private final String note;
        private int repointed;
        private int reinforced;
        private int disputed;
        private int retracted;
        private final List<String> conflictIds = new ArrayList<>();

        Merge(ScopedTx tx, String principal, String note) {
            this.tx = tx;
            this.principal = principal;
            this.note = note;
        }

        MergeOutcome run(String survivorId, String loserId, io.graphspindle.vocabulary.BrainVocabulary vocabulary) {
            // lock in id order so two opposite merges cannot deadlock
            EntityView first = lockEntity(tx, survivorId.compareTo(loserId) < 0 ? survivorId : loserId);
            EntityView second = lockEntity(tx, survivorId.compareTo(loserId) < 0 ? loserId : survivorId);
            EntityView survivor = first.id().equals(survivorId) ? first : second;
            EntityView loser = first.id().equals(loserId) ? first : second;
            requireActive(survivor);
            requireActive(loser);
            if (SELF_NORM.equals(loser.normalizedName())) {
                throw new GraphSpindleException(ErrorCode.CONFLICT_STATE,
                        "the self entity cannot be merged into another entity (merge the other entity into it)");
            }
            if (!vocabulary.compatible(survivor.type(), loser.type())) {
                throw GraphSpindleException.validation("cannot merge a " + loser.type() + " into a " + survivor.type()
                        + ": the types are not compatible");
            }
            Set<String> attributePredicates = new TreeSet<>();
            List<ClaimView> touching = tx.run(KnowledgeStatements.CLAIMS_TOUCHING, P.of("id", loserId))
                    .list(r -> Rows.claim(r.get("c")));
            for (ClaimView c : touching) {
                if (!c.relation()) {
                    attributePredicates.add(c.predicate());
                }
                repoint(c, loserId, survivorId);
            }
            Map<String, Object> clear = new LinkedHashMap<>();
            loser.attributes().keySet().forEach(k -> clear.put("attr_" + k, null));
            if (!clear.isEmpty()) {
                entities.setAttributes(tx, loserId, clear);
            }
            for (String predicate : attributePredicates) {
                projections.recomputeAttribute(tx, survivorId, predicate);
            }
            int aliasesMoved = (int) tx.run(KnowledgeStatements.MOVE_ALIASES, P.of("loserId", loserId, "survivorId",
                    survivorId)).single().get("n").asLong();
            tx.run(KnowledgeStatements.MOVE_MENTIONS, P.of("loserId", loserId, "survivorId", survivorId)).consume();
            tx.run(KnowledgeStatements.RECOUNT_MENTIONS, P.of("id", survivorId)).consume();
            tx.run(KnowledgeStatements.MOVE_DESCRIBES, P.of("loserId", loserId, "survivorId", survivorId)).consume();
            tx.run(KnowledgeStatements.INHERIT_DESCRIPTION, P.of("loserId", loserId, "survivorId", survivorId)).consume();
            String resolutionNote = Normalizer.truncate(note == null ? "merged" : note, 500);
            List<String> resolved = new ArrayList<>();
            tx.run(KnowledgeStatements.RESOLVE_DUPLICATES_BETWEEN, P.of("a", survivorId, "b", loserId, "by", principal,
                    "note", resolutionNote)).list(r -> r.get("id").asString()).forEach(resolved::add);
            tx.run(KnowledgeStatements.REPOINT_INVOLVES, P.of("loserId", loserId, "survivorId", survivorId)).consume();
            entities.markMerged(tx, loserId, survivorId, principal);
            return new MergeOutcome(survivorId, loserId, repointed, reinforced, disputed, retracted, aliasesMoved,
                    conflictIds, resolved);
        }

        private void repoint(ClaimView c, String loserId, String survivorId) {
            boolean wasActive = c.active();
            if (wasActive) {
                tx.run(KnowledgeStatements.CLEAR_KEYS, P.of("id", c.id())).consume();
                if (c.relation()) {
                    projections.sync(tx, new ClaimView(c.id(), c.kind(), c.predicate(), c.subjectId(), c.objectId(),
                            c.valueType(), c.value(), c.valueNorm(), c.polarity(), c.statement(), "REPOINTING",
                            c.confidence(), c.origin(), c.cardinality(), null, null, c.evidenceCount(), c.validFrom(),
                            c.validTo(), c.createdByPrincipal(), c.createdByIngestionId(), c.statusReason()));
                }
            }
            ClaimView now = c;
            if (loserId.equals(c.subjectId())) {
                now = Rows.claim(tx.run(KnowledgeStatements.REPOINT_SUBJECT, P.of("claimId", c.id(), "fromId", loserId,
                        "toId", survivorId)).single().get("c"));
            }
            if (loserId.equals(c.objectId())) {
                now = Rows.claim(tx.run(KnowledgeStatements.REPOINT_OBJECT, P.of("claimId", c.id(), "fromId", loserId,
                        "toId", survivorId)).single().get("c"));
            }
            repointed++;
            if (now.relation() && now.subjectId().equals(now.objectId())) {
                if (wasActive || "DISPUTED".equals(now.status())) {
                    ClaimView r = claims.leaveActive(tx, now.id(), "RETRACTED", "self-loop after entity merge", null,
                            null);
                    projections.sync(tx, r);
                    retracted++;
                }
                return;
            }
            if (!wasActive) {
                return;
            }
            Keys keys = keysOf(tx.brainId(), now);
            Optional<ClaimView> same = claims.findByActiveKey(tx, keys.activeKey());
            if (same.isPresent()) {
                absorb(same.get(), now);
                ClaimView s = claims.leaveActive(tx, now.id(), "SUPERSEDED", "duplicate after entity merge", null,
                        null);
                projections.sync(tx, s);
                claims.supersedes(tx, same.get().id(), now.id(), "duplicate after entity merge");
                reinforced++;
                return;
            }
            Optional<ClaimView> blocker = blocker(tx, now, keys, null);
            if (blocker.isPresent()) {
                ClaimView d = claims.leaveActive(tx, now.id(), "DISPUTED", "contradiction after entity merge", null,
                        null);
                projections.sync(tx, d);
                String conflictId = conflicts.create(tx, "CONTRADICTION", "Entity merge: \"" + blocker.get().statement()
                        + "\" vs \"" + now.statement() + "\"", null);
                conflicts.involveClaim(tx, conflictId, blocker.get().id(), "EXISTING");
                conflicts.involveClaim(tx, conflictId, now.id(), "INCOMING");
                conflictIds.add(conflictId);
                disputed++;
                return;
            }
            ClaimView active = activate(tx, now.id(), keys, now.statusReason());
            projections.sync(tx, active);
        }

        /** The survivor's claim takes over the loser copy's evidence and citations. */
        private void absorb(ClaimView keep, ClaimView dup) {
            tx.run(KnowledgeStatements.COPY_EVIDENCE, P.of("fromId", dup.id(), "toId", keep.id())).consume();
            tx.run(KnowledgeStatements.COPY_CITES, P.of("fromId", dup.id(), "toId", keep.id())).consume();
            tx.run(KnowledgeStatements.ABSORB, P.of("id", keep.id(), "confidence", dup.confidence(), "manual",
                    "MANUAL".equals(dup.origin()))).consume();
        }
    }

    // ------------------------------------------------------------------ conflict resolution (SCHEMA §9.4)

    /** Resolution values (SCHEMA §5.4 Conflict.resolution). */
    public enum Resolution {
        KEEP_EXISTING,
        ACCEPT_INCOMING,
        KEEP_BOTH,
        MERGE,
        NOT_DUPLICATE
    }

    /**
     * A resolution request.
     *
     * @param survivorId for MERGE: CANDIDATE_A or CANDIDATE_B (default CANDIDATE_B, the existing entity a
     *     proposal pointed at)
     * @param validFrom for KEEP_BOTH: optional new validFrom of the incoming claim
     * @param validTo for KEEP_BOTH: optional new validTo of the incoming claim
     */
    public record ResolutionRequest(Resolution resolution, String note, String survivorId, PartialDate validFrom,
            PartialDate validTo) {
    }

    /** The resolved conflict, the claims whose status changed, and the merge outcome for MERGE. */
    public record ResolutionOutcome(String conflictId, List<String> changedClaimIds, MergeOutcome merge) {

        public ResolutionOutcome {
            changedClaimIds = List.copyOf(changedClaimIds);
        }
    }

    public ResolutionOutcome resolveConflict(BrainScope scope, io.graphspindle.vocabulary.BrainVocabulary vocabulary,
            String conflictId, ResolutionRequest request, String principal) {
        return transactions.write(scope, tx -> {
            BrainLock.lockActive(tx);
            var lock = tx.run(KnowledgeStatements.LOCK_CONFLICT, P.of("id", conflictId)).list();
            if (lock.isEmpty()) {
                throw GraphSpindleException.notFound("conflict", conflictId);
            }
            if (!"OPEN".equals(lock.get(0).get("status").asString())) {
                throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "conflict is not OPEN");
            }
            ConflictView x = tx.run(io.graphspindle.graph.internal.RetrievalStatements.CONFLICT, P.of("id", conflictId))
                    .list().stream().findFirst().map(r -> Rows.conflict(r.get("x"), r.get("involves"),
                            r.get("raisedBy"))).orElseThrow();
            Resolution res = request.resolution();
            List<String> changed = new ArrayList<>();
            MergeOutcome merge = null;
            String status = "RESOLVED";
            boolean duplicate = "DUPLICATE_CANDIDATE".equals(x.kind());
            if (duplicate != (res == Resolution.MERGE || res == Resolution.NOT_DUPLICATE)) {
                throw GraphSpindleException.validation(res + " does not apply to a " + x.kind() + " conflict");
            }
            String note = request.note() == null ? null : Normalizer.truncate(request.note(), 500);
            switch (res) {
                case KEEP_EXISTING -> {
                    String incoming = x.claim("INCOMING");
                    if (incoming != null) {
                        ClaimView in = claims.require(tx, incoming);
                        if ("DISPUTED".equals(in.status())) {
                            ClaimView r = claims.leaveActive(tx, in.id(), "RETRACTED",
                                    "conflict resolved: KEEP_EXISTING", note, null);
                            projections.sync(tx, r);
                            changed.add(in.id());
                        }
                    }
                }
                case ACCEPT_INCOMING -> changed.addAll(acceptIncoming(tx, x, note));
                case KEEP_BOTH -> changed.addAll(keepBoth(tx, x, request));
                case MERGE -> {
                    String a = x.entity("CANDIDATE_A");
                    String b = x.entity("CANDIDATE_B");
                    if (a == null || b == null) {
                        throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "conflict has no candidate pair");
                    }
                    String survivor = request.survivorId() == null ? b : request.survivorId();
                    if (!survivor.equals(a) && !survivor.equals(b)) {
                        throw GraphSpindleException.validation("survivorId must be one of the two candidates");
                    }
                    String loser = survivor.equals(a) ? b : a;
                    merge = new Merge(tx, principal, note == null ? "duplicate candidate merged" : note)
                            .run(survivor, loser, vocabulary);
                }
                case NOT_DUPLICATE -> status = "DISMISSED";
            }
            if (tx.run(KnowledgeStatements.RESOLVE_CONFLICT, P.of("id", conflictId, "status", status, "resolution",
                    res.name(), "by", principal, "note", note)).list().isEmpty() && res != Resolution.MERGE) {
                throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "conflict is not OPEN");
            }
            return new ResolutionOutcome(conflictId, changed, merge);
        });
    }

    /** ACCEPT_INCOMING: the existing claim is SUPERSEDED and the disputed incoming claim becomes ACTIVE. */
    private List<String> acceptIncoming(ScopedTx tx, ConflictView x, String note) {
        String incomingId = x.claim("INCOMING");
        if (incomingId == null) {
            throw new GraphSpindleException(ErrorCode.CONFLICT_STATE,
                    "this conflict has no incoming claim to accept; correct the existing claim instead");
        }
        ClaimView in = claims.require(tx, incomingId);
        if (!"DISPUTED".equals(in.status())) {
            throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "the incoming claim is " + in.status());
        }
        List<String> changed = new ArrayList<>();
        String existingId = x.claim("EXISTING");
        ClaimView existing = existingId == null ? null : claims.require(tx, existingId);
        Keys keys = keysOf(tx.brainId(), in);
        String reason = "conflict resolved: ACCEPT_INCOMING";
        if (existing != null && existing.active()) {
            Optional<ClaimView> other = blocker(tx, in, keys, existing.id());
            if (other.isPresent() || claims.findByActiveKey(tx, keys.activeKey()).filter(c -> !c.id().equals(
                    existing.id())).isPresent()) {
                throw new GraphSpindleException(ErrorCode.CONFLICT_STATE,
                        "another active claim blocks the incoming claim", Map.of("claimId",
                                other.map(ClaimView::id).orElse("")), null);
            }
            ClaimView s = claims.leaveActive(tx, existing.id(), "SUPERSEDED", reason, note, null);
            projections.sync(tx, s);
            changed.add(existing.id());
        } else {
            requireUnblocked(tx, in, keys, null);
        }
        projections.sync(tx, activate(tx, in.id(), keys, reason));
        if (existing != null) {
            claims.supersedes(tx, in.id(), existing.id(), reason);
        }
        changed.add(in.id());
        return changed;
    }

    /**
     * KEEP_BOTH (SCHEMA §9.4): only for cardinality-MANY claims of the same triple with opposite
     * polarity whose validity ranges do not overlap (after the optional validFrom/validTo edits of
     * the incoming claim). The incoming claim becomes ACTIVE next to the existing one.
     */
    private List<String> keepBoth(ScopedTx tx, ConflictView x, ResolutionRequest request) {
        String incomingId = x.claim("INCOMING");
        String existingId = x.claim("EXISTING");
        if (incomingId == null || existingId == null) {
            throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "KEEP_BOTH needs an existing and an incoming claim");
        }
        ClaimView in = claims.require(tx, incomingId);
        ClaimView ex = claims.require(tx, existingId);
        if (!"DISPUTED".equals(in.status())) {
            throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "the incoming claim is " + in.status());
        }
        boolean oppositeTriple = in.subjectId().equals(ex.subjectId()) && in.predicate().equals(ex.predicate())
                && java.util.Objects.equals(in.objectId(), ex.objectId())
                && java.util.Objects.equals(in.valueNorm(), ex.valueNorm()) && !in.polarity().equals(ex.polarity());
        if (!"MANY".equals(in.cardinality()) || !oppositeTriple) {
            throw new GraphSpindleException(ErrorCode.CONFLICT_STATE,
                    "KEEP_BOTH applies only to cardinality-MANY claims of the same fact with opposite polarity");
        }
        String from = request.validFrom() != null ? request.validFrom().value() : in.validFrom();
        String to = request.validTo() != null ? request.validTo().value() : in.validTo();
        if (!disjoint(sort(from), sort(to), sort(ex.validFrom()), sort(ex.validTo()))) {
            throw new GraphSpindleException(ErrorCode.CONFLICT_STATE,
                    "KEEP_BOTH needs non-overlapping validity ranges; set validFrom/validTo of the incoming claim");
        }
        if (request.validFrom() != null || request.validTo() != null) {
            tx.run(KnowledgeStatements.SET_VALIDITY, P.of("id", in.id(), "validFrom", from, "validFromSort", sort(from),
                    "validTo", to, "validToSort", sort(to))).consume();
        }
        Keys keys = keysOf(tx.brainId(), in);
        requireUnblocked(tx, in, keys, ex.id());
        projections.sync(tx, activate(tx, in.id(), keys, "conflict resolved: KEEP_BOTH"));
        return List.of(in.id());
    }

    private static String sort(String partialDate) {
        return partialDate == null ? null : PartialDate.parse(partialDate).map(PartialDate::sortKey).orElse(null);
    }

    /** Ranges [from, to) with open ends; disjoint only when one ends before (or when) the other starts. */
    static boolean disjoint(String fromA, String toA, String fromB, String toB) {
        return (toA != null && fromB != null && toA.compareTo(fromB) <= 0)
                || (toB != null && fromA != null && toB.compareTo(fromA) <= 0);
    }

    /** No other ACTIVE claim with the same key, in the same slot or with the opposite polarity (except {@code allowId}). */
    private void requireUnblocked(ScopedTx tx, ClaimView c, Keys keys, String allowId) {
        Optional<ClaimView> other = claims.findByActiveKey(tx, keys.activeKey());
        if (other.isEmpty()) {
            other = blocker(tx, c, keys, allowId);
        }
        if (other.isPresent()) {
            throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "another active claim blocks this claim",
                    Map.of("claimId", other.get().id()), null);
        }
    }

    // ------------------------------------------------------------------ shared helpers

    private record Keys(String activeKey, String slotKey) {
    }

    /** Claim keys (SCHEMA §7.4) of a stored claim at its current subject/object. */
    private static Keys keysOf(String brainId, ClaimView c) {
        ClaimKind kind = ClaimKind.valueOf(c.kind());
        Polarity polarity = Polarity.valueOf(c.polarity());
        String objPart = ClaimKeys.objectPart(kind, c.objectId(),
                c.valueType() == null ? null : ValueType.valueOf(c.valueType()), c.valueNorm());
        Cardinality cardinality = c.cardinality() == null ? Cardinality.MANY : Cardinality.valueOf(c.cardinality());
        return new Keys(ClaimKeys.activeKey(brainId, c.subjectId(), c.predicate(), objPart, polarity),
                ClaimKeys.slotKey(brainId, c.subjectId(), c.predicate(), cardinality, polarity).orElse(null));
    }

    /** An ACTIVE claim in the same ONE slot or with the opposite polarity of the same triple (not {@code ignoreId}). */
    private Optional<ClaimView> blocker(ScopedTx tx, ClaimView c, Keys keys, String ignoreId) {
        if (keys.slotKey() != null) {
            Optional<ClaimView> slot = claims.findBySlotKey(tx, keys.slotKey()).filter(o -> !o.id().equals(c.id())
                    && !o.id().equals(ignoreId));
            if (slot.isPresent()) {
                return slot;
            }
        }
        Polarity opposite = "POSITIVE".equals(c.polarity()) ? Polarity.NEGATIVE : Polarity.POSITIVE;
        ClaimKind kind = ClaimKind.valueOf(c.kind());
        String oppositeKey = ClaimKeys.activeKey(tx.brainId(), c.subjectId(), c.predicate(), ClaimKeys.objectPart(kind,
                c.objectId(), c.valueType() == null ? null : ValueType.valueOf(c.valueType()), c.valueNorm()), opposite);
        return claims.findByActiveKey(tx, oppositeKey).filter(o -> !o.id().equals(c.id()) && !o.id().equals(ignoreId));
    }

    private ClaimView activate(ScopedTx tx, String claimId, Keys keys, String reason) {
        return Rows.claim(tx.run(KnowledgeStatements.ACTIVATE, P.of("id", claimId, "activeKey", keys.activeKey(),
                "slotKey", keys.slotKey(), "reason", reason)).single().get("c"));
    }

    private static EntityView lockEntity(ScopedTx tx, String id) {
        return tx.run(KnowledgeStatements.LOCK_ENTITY, P.of("id", id)).list().stream().findFirst()
                .map(r -> Rows.entity(r.get("n"))).orElseThrow(() -> GraphSpindleException.notFound("entity", id));
    }

    private static void requireActive(EntityView e) {
        if (!"ACTIVE".equals(e.status())) {
            throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "entity is " + e.status() + ": " + e.id(),
                    e.mergedIntoId() == null ? Map.of() : Map.of("mergedIntoId", e.mergedIntoId()), null);
        }
    }
}
