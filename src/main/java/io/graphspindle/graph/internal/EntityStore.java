package io.graphspindle.graph.internal;

import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Ids;
import io.graphspindle.graph.EntityView;
import io.graphspindle.graph.ScoredEntity;
import io.graphspindle.vocabulary.BrainVocabulary;
import java.util.List;
import java.util.Optional;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Component;

/** Entity writes and reads inside a brain-scoped transaction (SCHEMA §9.1). */
@Component
public class EntityStore {

    /** Result of {@link #mergeByKey}: the (survivor) id and whether a node was created. */
    public record Merged(String id, String type, boolean created) {
    }

    public Merged mergeByKey(ScopedTx tx, BrainVocabulary vocabulary, String type, String canonicalKey, String name,
            String normalizedName, String description, String origin, String ingestionId) {
        Tokens.entityLabel(vocabulary, type);
        Record r = tx.run(EntityStatements.MERGE_BY_KEY, P.of("canonicalKey", canonicalKey, "id", Ids.newId(), "type",
                type, "name", name, "normalizedName", normalizedName, "description", description, "origin", origin,
                "ingestionId", ingestionId)).single();
        String id = r.get("id").asString();
        if ("MERGED".equals(r.get("status").asString())) {
            EntityView survivor = resolve(tx, id).orElseThrow();
            return new Merged(survivor.id(), survivor.type(), false);
        }
        return new Merged(id, r.get("type").asString(), r.get("created").asBoolean());
    }

    public Optional<EntityView> findById(ScopedTx tx, String id) {
        return tx.run(EntityStatements.FIND_BY_ID, P.of("id", id)).list().stream().findFirst()
                .map(r -> Rows.entity(r.get("n")));
    }

    public Optional<EntityView> findByKey(ScopedTx tx, String canonicalKey) {
        return tx.run(EntityStatements.FIND_BY_KEY, P.of("canonicalKey", canonicalKey)).list().stream().findFirst()
                .map(r -> Rows.entity(r.get("n")));
    }

    /** The entity, or the survivor it was merged into (at most 5 hops). Empty when not in this brain. */
    public Optional<EntityView> resolve(ScopedTx tx, String id) {
        return tx.run(EntityStatements.RESOLVE, P.of("id", id)).list().stream().findFirst()
                .map(r -> Rows.entity(r.get("n")));
    }

    public EntityView require(ScopedTx tx, String id) {
        return resolve(tx, id).orElseThrow(() -> GraphSpindleException.notFound("entity", id));
    }

    public void markMerged(ScopedTx tx, String loserId, String survivorId, String by) {
        if (loserId.equals(survivorId)
                || tx.run(EntityStatements.MARK_MERGED, P.of("loserId", loserId, "survivorId", survivorId, "by", by))
                        .list().isEmpty()) {
            throw GraphSpindleException.notFound("entity", loserId + "/" + survivorId);
        }
    }

    /** Sets the description; {@code locked} sets {@code descriptionLocked} (a MANUAL edit locks it). */
    public void updateDescription(ScopedTx tx, String id, String description, boolean locked) {
        if (tx.run(EntityStatements.UPDATE_DESCRIPTION, P.of("id", id, "description", description, "locked", locked))
                .list().isEmpty()) {
            throw GraphSpindleException.notFound("entity", id);
        }
    }

    public boolean mention(ScopedTx tx, String id, String ingestionId, String surfaceForm) {
        List<Record> rows = tx.run(EntityStatements.MENTION, P.of("id", id, "ingestionId", ingestionId, "surfaceForm",
                surfaceForm)).list();
        return !rows.isEmpty() && rows.get(0).get("isNew").asBoolean();
    }

    public void setAttributes(ScopedTx tx, String id, java.util.Map<String, Object> attrProps) {
        attrProps.keySet().forEach(k -> {
            if (!k.startsWith("attr_")) {
                throw new IllegalArgumentException("only attr_* properties are projected");
            }
        });
        tx.run(EntityStatements.SET_ATTRIBUTES, P.of("id", id, "props", attrProps)).consume();
    }

    /** Fulltext search within the brain; {@code text} is escaped, never passed to Lucene raw. */
    public List<ScoredEntity> fulltext(ScopedTx tx, String text, int limit) {
        return fulltext(tx, text, limit, false);
    }

    /** Fulltext over name and description; {@code fuzzy} adds one-typo variants (candidate lookup). */
    public List<ScoredEntity> fulltext(ScopedTx tx, String text, int limit, boolean fuzzy) {
        String query = fuzzy ? Lucene.scopedFuzzy(tx.brainId(), text, "name", "description")
                : Lucene.scoped(tx.brainId(), text, "name", "description");
        if (query.isBlank()) {
            return List.of();
        }
        return tx.run(EntityStatements.FULLTEXT, P.of("query", query, "limit", limit, "oversample", limit * 5L))
                .list(r -> new ScoredEntity(Rows.entity(r.get("n")), r.get("score").asDouble()));
    }
}
