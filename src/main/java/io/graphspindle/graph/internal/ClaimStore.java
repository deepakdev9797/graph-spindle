package io.graphspindle.graph.internal;

import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.graph.ClaimView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Claim persistence (SCHEMA §9.2). Keys exist only while ACTIVE; {@link #leaveActive} removes them. */
@Component
public class ClaimStore {

    public Optional<ClaimView> findById(ScopedTx tx, String id) {
        return one(tx, ClaimStatements.FIND_BY_ID, P.of("id", id));
    }

    public Optional<ClaimView> findByActiveKey(ScopedTx tx, String activeKey) {
        return one(tx, ClaimStatements.FIND_BY_ACTIVE_KEY, P.of("activeKey", activeKey));
    }

    public Optional<ClaimView> findBySlotKey(ScopedTx tx, String slotKey) {
        return one(tx, ClaimStatements.FIND_BY_SLOT_KEY, P.of("slotKey", slotKey));
    }

    public List<ClaimView> ofSubject(ScopedTx tx, String subjectId) {
        return tx.run(ClaimStatements.OF_SUBJECT, P.of("subjectId", subjectId)).list(r -> Rows.claim(r.get("c")));
    }

    public ClaimView require(ScopedTx tx, String id) {
        return findById(tx, id).orElseThrow(() -> GraphSpindleException.notFound("claim", id));
    }

    /**
     * Creates a claim with its SUBJECT (and OBJECT) edges. {@code props} holds the claim properties
     * (SCHEMA §5.4) except brainId and the timestamps.
     */
    public ClaimView create(ScopedTx tx, Map<String, Object> props) {
        if (props.containsKey("brainId")) {
            throw new IllegalArgumentException("brainId is bound by the scope");
        }
        String subjectId = (String) props.get("subjectId");
        String objectId = (String) props.get("objectId");
        boolean relation = "RELATION".equals(props.get("kind"));
        if (relation != (objectId != null)) {
            throw new IllegalArgumentException("relation claims need an object, attribute claims none");
        }
        var rows = tx.run(relation ? ClaimStatements.CREATE_RELATION : ClaimStatements.CREATE_ATTRIBUTE,
                P.of("subjectId", subjectId, "objectId", objectId, "props", props)).list();
        if (rows.isEmpty()) {
            throw GraphSpindleException.notFound("entity", relation ? subjectId + "/" + objectId : subjectId);
        }
        return Rows.claim(rows.get(0).get("c"));
    }

    public ClaimView addEvidence(ScopedTx tx, String claimId, String ingestionId, String quote, double confidence) {
        var rows = tx.run(ClaimStatements.ADD_EVIDENCE, P.of("claimId", claimId, "ingestionId", ingestionId, "quote",
                quote, "confidence", confidence)).list();
        if (rows.isEmpty()) {
            throw GraphSpindleException.notFound("claim", claimId);
        }
        return Rows.claim(rows.get(0).get("c"));
    }

    public void cite(ScopedTx tx, String claimId, String sourceId) {
        if (tx.run(ClaimStatements.CITE, P.of("claimId", claimId, "sourceId", sourceId)).list().isEmpty()) {
            throw GraphSpindleException.notFound("source", sourceId);
        }
    }

    public ClaimView leaveActive(ScopedTx tx, String claimId, String status, String reason, String evidence,
            String ingestionId) {
        if ("ACTIVE".equals(status)) {
            throw new IllegalArgumentException("use create for ACTIVE claims");
        }
        var rows = tx.run(ClaimStatements.LEAVE_ACTIVE, P.of("claimId", claimId, "status", status, "reason", reason,
                "evidence", evidence, "ingestionId", ingestionId)).list();
        if (rows.isEmpty()) {
            throw GraphSpindleException.notFound("claim", claimId);
        }
        return Rows.claim(rows.get(0).get("c"));
    }

    public void supersedes(ScopedTx tx, String newId, String oldId, String reason) {
        if (tx.run(ClaimStatements.SUPERSEDES, P.of("newId", newId, "oldId", oldId, "reason", reason)).list()
                .isEmpty()) {
            throw GraphSpindleException.notFound("claim", newId + "/" + oldId);
        }
    }

    public void setValidTo(ScopedTx tx, String claimId, String validTo, String validToSort) {
        tx.run(ClaimStatements.SET_VALID_TO, P.of("claimId", claimId, "validTo", validTo, "validToSort", validToSort))
                .consume();
    }

    /** Active positive values of an attribute slot, sorted, with the stored cardinality of the first one. */
    public List<Object[]> activeAttributeValues(ScopedTx tx, String subjectId, String predicate) {
        return tx.run(ClaimStatements.ACTIVE_ATTRIBUTE_VALUES, P.of("subjectId", subjectId, "predicate", predicate))
                .list(r -> new Object[] {r.get("value").asString(), r.get("cardinality").asString(null)});
    }

    private static Optional<ClaimView> one(ScopedTx tx, String cypher, Map<String, Object> params) {
        return tx.run(cypher, params).list().stream().findFirst().map(r -> Rows.claim(r.get("c")));
    }
}
