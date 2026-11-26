package io.graphspindle.graph;

/** A stored claim (the source of truth for relations and attributes). */
public record ClaimView(String id, String kind, String predicate, String subjectId, String objectId,
        String valueType, String value, String valueNorm, String polarity, String statement, String status,
        double confidence, String origin, String cardinality, String activeKey, String slotKey, long evidenceCount,
        String validFrom, String validTo, String createdByPrincipal, String createdByIngestionId,
        String statusReason) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }

    public boolean relation() {
        return "RELATION".equals(kind);
    }
}
