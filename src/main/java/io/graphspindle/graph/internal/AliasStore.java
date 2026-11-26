package io.graphspindle.graph.internal;

import io.graphspindle.graph.EntityView;
import java.util.List;
import org.springframework.stereotype.Component;

/** Alias upsert and exact lookup. */
@Component
public class AliasStore {

    /** Upserts the alias and links it; returns true when the HAS_ALIAS edge is new. */
    public boolean upsert(ScopedTx tx, String entityId, String normalized, String display, String origin) {
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        return tx.run(AliasStatements.UPSERT, P.of("entityId", entityId, "normalized", normalized, "display", display,
                "origin", origin)).list().stream().anyMatch(r -> r.get("isNew").asBoolean());
    }

    public List<EntityView> lookup(ScopedTx tx, String normalized) {
        return tx.run(AliasStatements.LOOKUP, P.of("normalized", normalized)).list(r -> Rows.entity(r.get("n")));
    }

    public List<String> ofEntity(ScopedTx tx, String entityId) {
        return tx.run(AliasStatements.OF_ENTITY, P.of("entityId", entityId)).list(r -> r.get("normalized").asString());
    }
}
