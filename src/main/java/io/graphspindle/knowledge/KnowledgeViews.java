package io.graphspindle.knowledge;

import io.graphspindle.common.BrainScope;
import io.graphspindle.graph.AliasView;
import io.graphspindle.graph.ClaimView;
import io.graphspindle.graph.EntityView;
import io.graphspindle.graph.EvidenceView;
import io.graphspindle.graph.GraphReader;
import io.graphspindle.graph.SourceView;
import io.graphspindle.knowledge.KnowledgeModel.AliasInfo;
import io.graphspindle.knowledge.KnowledgeModel.ClaimInfo;
import io.graphspindle.knowledge.KnowledgeModel.EntityInfo;
import io.graphspindle.knowledge.KnowledgeModel.EvidenceInfo;
import io.graphspindle.knowledge.KnowledgeModel.SourceInfo;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Maps graph views to the service records (shared by the knowledge, retrieval and conflict services). */
@Component
public class KnowledgeViews {

    private final GraphReader reader;

    public KnowledgeViews(GraphReader reader) {
        this.reader = reader;
    }

    public static EntityInfo entity(EntityView e) {
        return new EntityInfo(e.id(), e.type(), e.name(), e.canonicalKey(), e.description(), e.descriptionLocked(),
                e.status(), e.origin(), e.mentionCount(), e.mergedIntoId(), e.attributes());
    }

    public static AliasInfo alias(AliasView a) {
        return new AliasInfo(a.normalized(), a.display(), a.origin());
    }

    public static SourceInfo source(SourceView s) {
        return new SourceInfo(s.id(), s.kind(), s.locator(), s.title(), s.summary(), s.mediaType(), s.createdAt(),
                s.lastSeenAt());
    }

    public static EvidenceInfo evidence(EvidenceView e) {
        return new EvidenceInfo(e.ingestionId(), e.quote(), e.confidence(), e.recordedAt(), e.principal());
    }

    /** Claims with subject and object names resolved in one read. */
    public List<ClaimInfo> claims(BrainScope scope, Collection<ClaimView> claims) {
        Set<String> ids = new LinkedHashSet<>();
        claims.forEach(c -> {
            ids.add(c.subjectId());
            if (c.objectId() != null) {
                ids.add(c.objectId());
            }
        });
        Map<String, EntityView> names = reader.entitiesByIds(scope, ids);
        return claims.stream().map(c -> claim(c, names)).toList();
    }

    public ClaimInfo claim(BrainScope scope, ClaimView c) {
        return claims(scope, List.of(c)).get(0);
    }

    static ClaimInfo claim(ClaimView c, Map<String, EntityView> names) {
        EntityView s = names.get(c.subjectId());
        EntityView o = c.objectId() == null ? null : names.get(c.objectId());
        return new ClaimInfo(c.id(), c.kind(), c.predicate(), c.subjectId(), s == null ? null : s.name(), c.objectId(),
                o == null ? null : o.name(), c.valueType(), c.value(), c.polarity(), c.statement(), c.status(),
                c.confidence(), c.origin(), c.cardinality(), c.evidenceCount(), c.validFrom(), c.validTo(),
                c.createdByPrincipal(), c.createdByIngestionId(), c.statusReason());
    }
}
