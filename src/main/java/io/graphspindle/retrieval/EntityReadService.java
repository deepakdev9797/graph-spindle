package io.graphspindle.retrieval;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.graphspindle.brain.BrainService;
import io.graphspindle.common.Actor;
import io.graphspindle.common.Permission;
import io.graphspindle.common.BrainScope;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.knowledge.KnowledgeModel.AliasInfo;
import io.graphspindle.knowledge.KnowledgeModel.EntityInfo;
import io.graphspindle.knowledge.KnowledgeModel.SourceInfo;
import io.graphspindle.knowledge.KnowledgeViews;
import io.graphspindle.retrieval.EntityContextService.ContextClaim;
import io.graphspindle.retrieval.EntityContextService.DisputedClaim;
import io.graphspindle.retrieval.EntityContextService.EntityContext;
import io.graphspindle.retrieval.NodeViewService.NodeView;
import io.graphspindle.retrieval.NodeViewService.Relationship;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Entity reads with the plain node view by default (ADR-024, ADR-026): node fields, properties and
 * relationships; claims, provenance and the agent text only with {@code include=claims}. With
 * {@code asOf} the view is what the brain believed then. Shared by REST and MCP (M8).
 */
@Service
public class EntityReadService {

    public static final String INCLUDE_CLAIMS = "claims";
    static final Set<String> INCLUDES = Set.of(INCLUDE_CLAIMS);

    private final BrainService brains;
    private final NodeViewService nodes;
    private final EntityContextService context;
    private final KnowledgeViews views;

    public EntityReadService(BrainService brains, NodeViewService nodes, EntityContextService context,
            KnowledgeViews views) {
        this.brains = brains;
        this.nodes = nodes;
        this.context = context;
        this.views = views;
    }

    /**
     * The plain view plus, with {@code include=claims}, the claim-level context. Fields of the
     * claim-level part are null (omitted in JSON) otherwise.
     *
     * @param entity with {@code include=claims}: the full entity record (as before ADR-024)
     * @param claims with {@code include=claims}: ACTIVE claims now, or the claims believed at {@code asOf}
     * @param disputed with {@code include=claims} and no {@code asOf}: DISPUTED claims and their conflicts
     */
    public record EntityRead(String id, String type, String name, String description, String status, String origin,
            String redirectedFrom, @JsonInclude(JsonInclude.Include.NON_NULL) Instant asOf,
            Map<String, Object> properties, List<Relationship> relationships, List<AliasInfo> aliases,
            boolean truncated, @JsonInclude(JsonInclude.Include.NON_NULL) EntityInfo entity,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<ContextClaim> claims,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<DisputedClaim> disputed,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<String> openConflictIds,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<SourceInfo> describedBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) String text, boolean untrusted) {
    }

    /** Parses a comma-separated or repeated {@code include} parameter. */
    public static boolean includesClaims(List<String> include) {
        boolean claims = false;
        if (include == null) {
            return false;
        }
        for (String raw : include) {
            for (String part : raw.split(",")) {
                String p = part.strip().toLowerCase(Locale.ROOT);
                if (p.isEmpty()) {
                    continue;
                }
                if (!INCLUDES.contains(p)) {
                    throw GraphSpindleException.validation("include must be one of " + INCLUDES);
                }
                claims = true;
            }
        }
        return claims;
    }

    public EntityRead read(Actor actor, String brainId, String entityId, Instant asOf, boolean includeClaims,
            Integer maxClaims) {
        // validated even when unused, so a bad value is never silently ignored
        io.graphspindle.common.Paging.limit(maxClaims, EntityContextService.DEFAULT_MAX_CLAIMS,
                EntityContextService.MAX_CLAIMS);
        NodeView n = asOf == null ? nodes.current(actor, brainId, entityId) : nodes.asOf(actor, brainId, entityId, asOf);
        if (!includeClaims) {
            return of(n, null, null, null, null, null, null);
        }
        if (asOf == null) {
            EntityContext c = context.context(actor, brainId, n.id(), maxClaims);
            return of(n, c.entity(), c.claims(), c.disputed(), c.openConflictIds(), c.describedBy(), c.text());
        }
        BrainScope scope = brains.requireActive(actor, brainId, Permission.READ);
        List<ContextClaim> believed = views.claims(scope, nodes.claimsAsOf(scope, n.id(), asOf)).stream()
                .map(c -> new ContextClaim(c, List.of())).toList();
        return of(n, null, believed, null, null, null, null);
    }

    private static EntityRead of(NodeView n, EntityInfo entity, List<ContextClaim> claims,
            List<DisputedClaim> disputed, List<String> open, List<SourceInfo> describedBy, String text) {
        return new EntityRead(n.id(), n.type(), n.name(), n.description(), n.status(), n.origin(), n.redirectedFrom(),
                n.asOf(), n.properties(), n.relationships(), n.aliases(), n.truncated(), entity, claims, disputed,
                open, describedBy, text, true);
    }
}
