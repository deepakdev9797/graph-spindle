package io.graphspindle.graph.internal;

import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Ids;
import io.graphspindle.common.Normalizer;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Conflict nodes with INVOLVES edges to claims or entities and RAISED_BY to the ingestion. */
@Component
public class ConflictStore {

    public String create(ScopedTx tx, String kind, String description, String ingestionId) {
        String id = Ids.newId();
        tx.run(ConflictStatements.CREATE, P.of("id", id, "kind", kind, "description",
                Normalizer.truncate(description, 500), "ingestionId", ingestionId)).consume();
        return id;
    }

    public void involveClaim(ScopedTx tx, String conflictId, String claimId, String role) {
        if (tx.run(ConflictStatements.INVOLVE_CLAIM, P.of("conflictId", conflictId, "targetId", claimId, "role", role))
                .list().isEmpty()) {
            throw GraphSpindleException.notFound("claim", claimId);
        }
    }

    public void involveEntity(ScopedTx tx, String conflictId, String entityId, String role) {
        if (tx.run(ConflictStatements.INVOLVE_ENTITY, P.of("conflictId", conflictId, "targetId", entityId, "role",
                role)).list().isEmpty()) {
            throw GraphSpindleException.notFound("entity", entityId);
        }
    }

    /** The DISPUTED incoming claim of an OPEN conflict (see {@link ConflictStatements#FIND_OPEN_DISPUTE}). */
    public record Dispute(io.graphspindle.graph.ClaimView claim, String conflictId) {
    }

    public Optional<Dispute> openDispute(ScopedTx tx, String kind, String existingId, String subjectId,
            String predicate, String polarity, String objectId, String valueType, String valueNorm) {
        return tx.run(ConflictStatements.FIND_OPEN_DISPUTE, P.of("kind", kind, "existingId", existingId, "subjectId",
                subjectId, "predicate", predicate, "polarity", polarity, "objectId", objectId == null ? "" : objectId,
                "valueType", valueType == null ? "" : valueType, "valueNorm", valueNorm == null ? "" : valueNorm))
                .list().stream().findFirst()
                .map(r -> new Dispute(Rows.claim(r.get("c")), r.get("conflictId").asString()));
    }

    public Optional<String> openDuplicate(ScopedTx tx, String a, String b) {
        return tx.run(ConflictStatements.FIND_OPEN_DUPLICATE, P.of("a", a, "b", b)).list().stream().findFirst()
                .map(r -> r.get("id").asString());
    }
}
