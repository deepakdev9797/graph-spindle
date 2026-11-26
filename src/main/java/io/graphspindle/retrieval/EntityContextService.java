package io.graphspindle.retrieval;

import io.graphspindle.brain.BrainService;
import io.graphspindle.common.Actor;
import io.graphspindle.common.BrainScope;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Paging;
import io.graphspindle.graph.ClaimView;
import io.graphspindle.graph.EntityView;
import io.graphspindle.graph.GraphReader;
import io.graphspindle.graph.SourceView;
import io.graphspindle.knowledge.KnowledgeModel.AliasInfo;
import io.graphspindle.knowledge.KnowledgeModel.ClaimInfo;
import io.graphspindle.knowledge.KnowledgeModel.EntityInfo;
import io.graphspindle.knowledge.KnowledgeModel.SourceInfo;
import io.graphspindle.knowledge.KnowledgeViews;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * "Tell me about X" (ARCHITECTURE §9): the entity, its aliases, its ACTIVE claims (as subject or
 * object, each with up to 3 cited sources), its DISPUTED claims with their open conflicts, and the
 * sources describing it. A MERGED id is followed to the survivor. Also rendered as a compact text
 * block for agents. Shared by REST and MCP ({@code get_entity_context}).
 */
@Service
public class EntityContextService {

    public static final int DEFAULT_MAX_CLAIMS = 30;
    public static final int MAX_CLAIMS = 100;
    static final int SOURCES_PER_CLAIM = 3;
    static final int MAX_DESCRIBING = 10;

    private final BrainService brains;
    private final GraphReader reader;
    private final KnowledgeViews views;

    public EntityContextService(BrainService brains, GraphReader reader, KnowledgeViews views) {
        this.brains = brains;
        this.reader = reader;
        this.views = views;
    }

    public record ContextClaim(ClaimInfo claim, List<SourceInfo> sources) {
    }

    public record DisputedClaim(ClaimInfo claim, List<String> openConflictIds) {
    }

    /**
     * @param redirectedFrom the requested id when it was MERGED into {@code entity}, else null
     * @param claims ACTIVE claims where the entity is subject or object (newest first)
     * @param openConflictIds OPEN conflicts involving the entity or its claims
     * @param text compact rendering for agents (graph text is untrusted data)
     */
    public record EntityContext(EntityInfo entity, String redirectedFrom, List<AliasInfo> aliases,
            List<ContextClaim> claims, List<DisputedClaim> disputed, List<String> openConflictIds,
            List<SourceInfo> describedBy, String text, boolean untrusted) {
    }

    public EntityContext context(Actor actor, String brainId, String entityId, Integer maxClaims) {
        BrainScope scope = brains.requireActive(brainId);
        int max = Paging.limit(maxClaims, DEFAULT_MAX_CLAIMS, MAX_CLAIMS);
        EntityView e = (Paging.plausibleId(entityId) ? reader.resolveEntity(scope, entityId)
                : java.util.Optional.<EntityView>empty())
                .orElseThrow(() -> GraphSpindleException.notFound("entity", entityId));
        List<ClaimView> active = reader.claimsOfEntity(scope, e.id(), List.of("ACTIVE"), max);
        List<ClaimView> disputed = reader.claimsOfEntity(scope, e.id(), List.of("DISPUTED"), max);
        Map<String, List<SourceView>> sources = reader.citedSources(scope, active.stream().map(ClaimView::id).toList(),
                SOURCES_PER_CLAIM);
        Map<String, List<String>> conflicts = reader.openConflictsOfClaims(scope,
                disputed.stream().map(ClaimView::id).toList());
        List<ClaimInfo> activeInfo = views.claims(scope, active);
        List<ClaimInfo> disputedInfo = views.claims(scope, disputed);
        List<ContextClaim> claims = new ArrayList<>();
        for (ClaimInfo c : activeInfo) {
            claims.add(new ContextClaim(c, sources.getOrDefault(c.id(), List.of()).stream().map(KnowledgeViews::source)
                    .toList()));
        }
        List<DisputedClaim> disputes = disputedInfo.stream().map(c -> new DisputedClaim(c,
                conflicts.getOrDefault(c.id(), List.of()))).toList();
        EntityInfo info = KnowledgeViews.entity(e);
        List<AliasInfo> aliases = reader.aliases(scope, e.id()).stream().map(KnowledgeViews::alias).toList();
        List<SourceInfo> describedBy = reader.sourcesDescribing(scope, e.id(), MAX_DESCRIBING).stream()
                .map(KnowledgeViews::source).toList();
        List<String> open = reader.openConflictsOfEntity(scope, e.id());
        String redirected = e.id().equals(entityId) ? null : entityId;
        String text = ContextRenderer.render(info, aliases, claims, disputes, open, describedBy);
        return new EntityContext(info, redirected, aliases, claims, disputes, open, describedBy, text, true);
    }
}
