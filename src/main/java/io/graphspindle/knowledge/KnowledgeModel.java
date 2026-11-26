package io.graphspindle.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Records returned and accepted by the knowledge, retrieval and conflict services. They are the
 * contract shared by REST (M5) and MCP tools (M8): adapters map nothing but transport details. Text
 * fields that come from the graph (names, descriptions, statements, summaries) are untrusted data
 * (ARCHITECTURE §8); result records that carry them say so with {@code untrusted = true}.
 */
public final class KnowledgeModel {

    private KnowledgeModel() {
    }

    // ------------------------------------------------------------------ views

    /** An entity. {@code attributes} are the projected attribute values without the {@code attr_} prefix. */
    public record EntityInfo(String id, String type, String name, String canonicalKey, String description,
            boolean descriptionLocked, String status, String origin, long mentionCount, String mergedIntoId,
            Map<String, Object> attributes) {
    }

    /** An alias: normalized form (the lookup key), first surface form, and who linked it. */
    public record AliasInfo(String normalized, String display, String origin) {
    }

    /**
     * A claim with the names of its subject and object (relation claims) for display.
     *
     * @param value canonical literal of an attribute claim, else null
     */
    public record ClaimInfo(String id, String kind, String predicate, String subjectId, String subjectName,
            String objectId, String objectName, String valueType, String value, String polarity, String statement,
            String status, double confidence, String origin, String cardinality, long evidenceCount, String validFrom,
            String validTo, String createdByPrincipal, String createdByIngestionId, String statusReason) {
    }

    public record SourceInfo(String id, String kind, String locator, String title, String summary, String mediaType,
            Instant createdAt, Instant lastSeenAt) {
    }

    /** One ingestion supporting a claim, with its evidence quote. */
    public record EvidenceInfo(String ingestionId, String quote, double confidence, Instant recordedAt,
            String principal) {
    }

    /** A claim with its provenance (SCHEMA R2): evidence, cited sources, supersession links and conflicts. */
    public record ClaimDetail(ClaimInfo claim, List<EvidenceInfo> evidence, List<SourceInfo> sources,
            List<String> supersedes, List<String> supersededBy, List<String> conflictIds, boolean untrusted) {
    }

    /** One element of a claim's SUPERSEDES chain (oldest first). */
    public record HistoryEntry(ClaimInfo claim, List<String> supersedes, List<String> supersededBy,
            String supersedeReason) {
    }

    /** A source reference (never dereferenced), the entities it describes and the claims citing it. */
    public record SourceDetail(SourceInfo source, List<EntityInfo> describes, List<String> citedByClaimIds,
            boolean untrusted) {
    }

    // ------------------------------------------------------------------ requests

    /**
     * A manual entity.
     *
     * @param description optional; when given it is locked against LLM updates
     * @param aliases optional extra names (at most 20)
     * @param distinct create a new entity even if one with the same type and name exists (key {@code ~2}, {@code ~3} ...)
     */
    public record NewEntity(String type, String name, String description, List<String> aliases, Boolean distinct) {
    }

    /** A manual entity edit; null fields are unchanged. A new description is locked unless {@code descriptionLocked=false}. */
    public record EntityChanges(String name, String description, Boolean descriptionLocked) {
    }

    public record NewAlias(String alias) {
    }

    /**
     * A manual claim (origin MANUAL, confidence 1.0). Exactly one of {@code objectId} (relation
     * predicate) or {@code value} (attribute predicate).
     *
     * @param polarity {@code POSITIVE} (default) or {@code NEGATIVE}
     * @param statement optional sentence; generated from the names when absent
     */
    public record NewClaim(String subjectId, String predicate, String objectId, String value, String polarity,
            String statement, String validFrom, String validTo) {
    }

    /** The new object or value of a superseded claim; subject and predicate stay those of the superseded claim. */
    public record Replacement(String objectId, String value, String polarity, String statement, String validFrom,
            String validTo) {
    }

    public record Supersession(String reason, Replacement replacement) {
    }

    public record Retraction(String reason) {
    }

    public record MergeRequest(String survivorId, String loserId, String reason) {
    }

    // ------------------------------------------------------------------ results

    public record EntityWrite(EntityInfo entity, boolean created, List<AliasInfo> aliases) {
    }

    /**
     * Result of a claim write.
     *
     * @param outcome {@code CREATED}, {@code REINFORCED}, {@code DISPUTED}, {@code DISPUTE_REINFORCED},
     *     {@code SUPERSEDED} or {@code RETRACTED}
     * @param conflictId the conflict opened (or reinforced) when the claim is DISPUTED
     * @param supersededClaimId for a supersession, the claim that was superseded
     */
    public record ClaimWrite(ClaimInfo claim, String outcome, String conflictId, String supersededClaimId) {
    }

    public record MergeResult(EntityInfo survivor, String loserId, int claimsRepointed, int claimsReinforced,
            int claimsDisputed, int claimsRetracted, int aliasesMoved, List<String> conflictIds,
            List<String> resolvedDuplicateConflictIds) {
    }
}
