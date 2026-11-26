package io.graphspindle.graph.internal;

import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Ids;
import io.graphspindle.graph.SourceInput;
import org.springframework.stereotype.Component;

/** SourceRef upsert by {@code (brainId, kind, locatorNorm)}; locators are never dereferenced (ADR-013). */
@Component
public class SourceRefStore {

    public String upsert(ScopedTx tx, SourceInput s, String ingestionId) {
        var rows = tx.run(SourceStatements.UPSERT, P.of("ingestionId", ingestionId, "kind", s.kind(), "locatorNorm",
                s.locatorNorm(), "id", Ids.newId(), "locator", s.locator(), "title", s.title(), "summary", s.summary(),
                "mediaType", s.mediaType(), "clientContentHash", s.clientContentHash())).list();
        if (rows.isEmpty()) {
            throw GraphSpindleException.notFound("ingestion", ingestionId);
        }
        return rows.get(0).get("id").asString();
    }

    public void describes(ScopedTx tx, String sourceId, String entityId) {
        if (tx.run(SourceStatements.DESCRIBES, P.of("sourceId", sourceId, "entityId", entityId)).list().isEmpty()) {
            throw GraphSpindleException.notFound("entity", entityId);
        }
    }
}
