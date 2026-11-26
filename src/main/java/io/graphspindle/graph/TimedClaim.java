package io.graphspindle.graph;

import java.time.Instant;
import java.util.List;

/**
 * A claim with its record-time facts, for point-in-time reads and history (ADR-026).
 *
 * @param claim the claim
 * @param recordedAt when the brain recorded it (transaction time)
 * @param statusChangedAt when its status last changed (equals {@code recordedAt} if never)
 * @param supersedes ids of the claims it superseded ({@code (this)-[:SUPERSEDES]->(old)})
 * @param supersedeReason the reason on the SUPERSEDES link, or null
 * @param supersededAt the earliest {@code recordedAt} of a claim that superseded this one, or null
 *     (read with the claim, so it is known even when the replacement is outside a timeline window)
 * @param originalSubjectId the subject before an entity merge re-pointed it, or null
 * @param subjectRepointedAt when the subject was re-pointed by a merge, or null
 * @param originalObjectId the object before an entity merge re-pointed it, or null
 * @param objectRepointedAt when the object was re-pointed by a merge, or null
 */
public record TimedClaim(ClaimView claim, Instant recordedAt, Instant statusChangedAt, List<String> supersedes,
        String supersedeReason, Instant supersededAt, String originalSubjectId, Instant subjectRepointedAt,
        String originalObjectId, Instant objectRepointedAt) {

    public TimedClaim {
        supersedes = List.copyOf(supersedes);
    }

    public TimedClaim(ClaimView claim, Instant recordedAt, Instant statusChangedAt, List<String> supersedes,
            String supersedeReason) {
        this(claim, recordedAt, statusChangedAt, supersedes, supersedeReason, null, null, null, null, null);
    }

    /** A copy with another claim view (the same record-time facts). */
    public TimedClaim withClaim(ClaimView view) {
        return new TimedClaim(view, recordedAt, statusChangedAt, supersedes, supersedeReason, supersededAt,
                originalSubjectId, subjectRepointedAt, originalObjectId, objectRepointedAt);
    }

    /** The subject at record time {@code at}: the original one before a merge re-pointed it. */
    public String subjectAt(Instant at) {
        return subjectRepointedAt != null && originalSubjectId != null && subjectRepointedAt.isAfter(at)
                ? originalSubjectId : claim.subjectId();
    }

    /** The object at record time {@code at}: the original one before a merge re-pointed it. */
    public String objectAt(Instant at) {
        return objectRepointedAt != null && originalObjectId != null && objectRepointedAt.isAfter(at)
                ? originalObjectId : claim.objectId();
    }
}
