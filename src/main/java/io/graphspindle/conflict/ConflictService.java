package io.graphspindle.conflict;

import io.graphspindle.brain.BrainService;
import io.graphspindle.common.Actor;
import io.graphspindle.common.BrainScope;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Normalizer;
import io.graphspindle.common.Page;
import io.graphspindle.common.Paging;
import io.graphspindle.common.PartialDate;
import io.graphspindle.graph.ClaimView;
import io.graphspindle.graph.ConflictView;
import io.graphspindle.graph.EntityView;
import io.graphspindle.graph.GraphReader;
import io.graphspindle.graph.KnowledgeStore;
import io.graphspindle.knowledge.KnowledgeModel.ClaimInfo;
import io.graphspindle.knowledge.KnowledgeModel.EntityInfo;
import io.graphspindle.knowledge.KnowledgeModel.MergeResult;
import io.graphspindle.knowledge.KnowledgeService;
import io.graphspindle.knowledge.KnowledgeViews;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Conflicts (contradictions, protected overrides, duplicate candidates): listing and manual
 * resolution (SCHEMA §9.4). Shared by REST and MCP ({@code list_conflicts}, {@code resolve_conflict}).
 */
@Service
public class ConflictService {

    static final Set<String> STATUSES = Set.of("OPEN", "RESOLVED", "DISMISSED");
    static final Set<String> KINDS = Set.of("CONTRADICTION", "DUPLICATE_CANDIDATE", "PROTECTED_OVERRIDE");

    private final BrainService brains;
    private final GraphReader reader;
    private final KnowledgeStore store;
    private final KnowledgeViews views;
    private final KnowledgeService knowledge;

    public ConflictService(BrainService brains, GraphReader reader, KnowledgeStore store, KnowledgeViews views,
            KnowledgeService knowledge) {
        this.brains = brains;
        this.reader = reader;
        this.store = store;
        this.views = views;
        this.knowledge = knowledge;
    }

    /** A conflict with the claims and entities it involves (by role). Graph text is untrusted. */
    public record ConflictInfo(String id, String kind, String status, String description, Instant createdAt,
            Instant resolvedAt, String resolution, String resolvedBy, String resolutionNote, String raisedByIngestionId,
            List<InvolvedClaim> claims, List<InvolvedEntity> entities, boolean untrusted) {
    }

    public record InvolvedClaim(String role, ClaimInfo claim) {
    }

    public record InvolvedEntity(String role, EntityInfo entity) {
    }

    /**
     * A resolution.
     *
     * @param resolution {@code KEEP_EXISTING}, {@code ACCEPT_INCOMING}, {@code KEEP_BOTH} (contradictions and
     *     protected overrides) or {@code MERGE}, {@code NOT_DUPLICATE} (duplicate candidates)
     * @param survivorId MERGE only: which candidate survives (default CANDIDATE_B)
     * @param validFrom KEEP_BOTH only: new validFrom of the incoming claim
     * @param validTo KEEP_BOTH only: new validTo of the incoming claim
     */
    public record Resolve(String resolution, String note, String survivorId, String validFrom, String validTo) {
    }

    public record ResolveResult(ConflictInfo conflict, List<ClaimInfo> changedClaims, MergeResult merge) {
    }

    public Page<ConflictInfo> list(Actor actor, String brainId, String status, String kind, String cursor,
            Integer limit) {
        BrainScope scope = brains.requireActive(brainId);
        String st = upper(status, STATUSES, "status");
        String k = upper(kind, KINDS, "kind");
        Page<ConflictView> page = reader.listConflicts(scope, st, k, Paging.cursor(cursor),
                Paging.limit(limit, Paging.DEFAULT_LIMIT, Paging.MAX_LIMIT));
        return new Page<>(info(scope, page.items()), page.next());
    }

    public ConflictInfo get(Actor actor, String brainId, String conflictId) {
        BrainScope scope = brains.requireActive(brainId);
        return info(scope, List.of(find(scope, conflictId))).get(0);
    }

    public ResolveResult resolve(Actor actor, String brainId, String conflictId, Resolve request) {
        BrainScope scope = brains.requireActive(brainId);
        if (request == null || request.resolution() == null) {
            throw GraphSpindleException.validation("resolution is required");
        }
        KnowledgeStore.Resolution resolution;
        try {
            resolution = KnowledgeStore.Resolution.valueOf(request.resolution().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw GraphSpindleException.validation("resolution must be one of "
                    + java.util.Arrays.toString(KnowledgeStore.Resolution.values()));
        }
        if (request.note() != null && Normalizer.codePointLength(request.note()) > 500) {
            throw GraphSpindleException.validation("note must be at most 500 characters");
        }
        String id = find(scope, conflictId).id();
        KnowledgeStore.ResolutionOutcome out = store.resolveConflict(scope, brains.vocabulary(scope), id,
                new KnowledgeStore.ResolutionRequest(resolution, request.note(), request.survivorId(),
                        date("validFrom", request.validFrom()), date("validTo", request.validTo())), actor.id());
        List<ClaimView> changed = new ArrayList<>();
        out.changedClaimIds().forEach(c -> reader.claim(scope, c).ifPresent(changed::add));
        return new ResolveResult(get(actor, brainId, id), views.claims(scope, changed),
                out.merge() == null ? null : knowledge.mergeResult(scope, out.merge()));
    }

    private ConflictView find(BrainScope scope, String id) {
        return (Paging.plausibleId(id) ? reader.conflict(scope, id) : java.util.Optional.<ConflictView>empty())
                .orElseThrow(() -> GraphSpindleException.notFound("conflict", id));
    }

    private List<ConflictInfo> info(BrainScope scope, List<ConflictView> conflicts) {
        Set<String> claimIds = new LinkedHashSet<>();
        Set<String> entityIds = new LinkedHashSet<>();
        conflicts.forEach(x -> x.involves().forEach(i -> ("Claim".equals(i.target()) ? claimIds : entityIds)
                .add(i.id())));
        Map<String, ClaimInfo> claims = new java.util.HashMap<>();
        List<ClaimView> found = new ArrayList<>();
        claimIds.forEach(c -> reader.claim(scope, c).ifPresent(found::add));
        views.claims(scope, found).forEach(c -> claims.put(c.id(), c));
        Map<String, EntityView> entities = reader.entitiesByIds(scope, entityIds);
        return conflicts.stream().map(x -> new ConflictInfo(x.id(), x.kind(), x.status(), x.description(),
                x.createdAt(), x.resolvedAt(), x.resolution(), x.resolvedBy(), x.resolutionNote(),
                x.raisedByIngestionId(),
                x.involves().stream().filter(i -> "Claim".equals(i.target()) && claims.containsKey(i.id()))
                        .map(i -> new InvolvedClaim(i.role(), claims.get(i.id()))).toList(),
                x.involves().stream().filter(i -> "Entity".equals(i.target()) && entities.containsKey(i.id()))
                        .map(i -> new InvolvedEntity(i.role(), KnowledgeViews.entity(entities.get(i.id())))).toList(),
                true)).toList();
    }

    private static String upper(String value, Set<String> allowed, String field) {
        if (value == null) {
            return null;
        }
        String v = value.toUpperCase(Locale.ROOT);
        if (!allowed.contains(v)) {
            throw GraphSpindleException.validation(field + " must be one of " + allowed);
        }
        return v;
    }

    private static PartialDate date(String field, String raw) {
        if (raw == null) {
            return null;
        }
        return PartialDate.parse(raw).orElseThrow(() -> GraphSpindleException.validation(field
                + " must be YYYY, YYYY-MM or YYYY-MM-DD"));
    }
}
