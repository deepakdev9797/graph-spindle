package io.graphspindle.graph;

import io.graphspindle.common.BrainScope;
import io.graphspindle.graph.internal.AliasStore;
import io.graphspindle.graph.internal.ClaimStore;
import io.graphspindle.graph.internal.EntityStore;
import io.graphspindle.graph.internal.GraphTransactions;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Brain-scoped read access to entities, aliases and claims (each call is one read transaction). */
@Component
public class GraphReader {

    private final GraphTransactions tx;
    private final EntityStore entities;
    private final AliasStore aliases;
    private final ClaimStore claims;

    public GraphReader(GraphTransactions tx, EntityStore entities, AliasStore aliases, ClaimStore claims) {
        this.tx = tx;
        this.entities = entities;
        this.aliases = aliases;
        this.claims = claims;
    }

    public Optional<EntityView> entity(BrainScope scope, String id) {
        return tx.read(scope, t -> entities.findById(t, id));
    }

    /** The entity or the survivor it was merged into. */
    public Optional<EntityView> resolveEntity(BrainScope scope, String id) {
        return tx.read(scope, t -> entities.resolve(t, id));
    }

    public Optional<EntityView> entityByKey(BrainScope scope, String canonicalKey) {
        return tx.read(scope, t -> entities.findByKey(t, canonicalKey));
    }

    public List<EntityView> entitiesByAlias(BrainScope scope, String normalized) {
        return tx.read(scope, t -> aliases.lookup(t, normalized));
    }

    public List<String> aliasesOf(BrainScope scope, String entityId) {
        return tx.read(scope, t -> aliases.ofEntity(t, entityId));
    }

    public List<ScoredEntity> fulltextEntities(BrainScope scope, String text, int limit) {
        return tx.read(scope, t -> entities.fulltext(t, text, limit));
    }

    public Optional<ClaimView> claim(BrainScope scope, String id) {
        return tx.read(scope, t -> claims.findById(t, id));
    }

    public List<ClaimView> claimsOfSubject(BrainScope scope, String subjectId) {
        return tx.read(scope, t -> claims.ofSubject(t, subjectId));
    }
}
