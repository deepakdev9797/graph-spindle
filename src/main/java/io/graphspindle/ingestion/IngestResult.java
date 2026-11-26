package io.graphspindle.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The outcome of an ingestion (ARCHITECTURE §3, §6). Stored with the Ingestion record and returned
 * again for idempotent replays. Messages and summaries can contain model or user text and are
 * therefore marked {@code untrusted}.
 *
 * @param status {@code APPLIED}, {@code NO_CHANGES}, {@code FAILED}, {@code RECEIVED} (still running)
 *     or {@code DRY_RUN} (nothing persisted)
 * @param replayed true when this is the stored result of an earlier request with the same requestId
 * @param duplicateOf the earlier ingestion with the same content hash (a new requestId within the
 *     brain's dedup window); nothing was processed again
 * @param opsProposed operations the model emitted
 * @param opsApplied operations that changed the graph
 * @param opsRejected operations rejected by the parser or validator
 * @param accepted the accepted operations' outcomes (several per operation are possible; opIndex
 *     -1 = an engine consequence or engine-added operation)
 * @param refs local refs of the proposal mapped to entity ids
 * @param planned for dry runs: the validated commands that would be applied
 * @param error why a FAILED ingestion failed
 */
public record IngestResult(String ingestionId, String requestId, String status, boolean replayed,
        String duplicateOf, boolean dryRun, String summary, int opsProposed, int opsApplied, int opsRejected,
        List<Outcome> accepted, List<Rejected> rejected, List<Adjusted> adjusted, Map<String, String> refs,
        List<Planned> planned, LlmInfo llm, Error error, Instant receivedAt, Instant completedAt,
        boolean untrusted) {

    public IngestResult {
        accepted = accepted == null ? List.of() : List.copyOf(accepted);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
        adjusted = adjusted == null ? List.of() : List.copyOf(adjusted);
        refs = refs == null ? Map.of() : Map.copyOf(refs);
        planned = planned == null ? List.of() : List.copyOf(planned);
        untrusted = true;
    }

    public record Outcome(int opIndex, String outcome, String entityId, String claimId, String conflictId) {
    }

    public record Rejected(int opIndex, String op, String code, String message) {
    }

    public record Adjusted(int opIndex, String op, String kind, String message) {
    }

    public record Planned(int opIndex, String command, String detail) {
    }

    /**
     * @param calls LLM calls made (including repairs)
     * @param repairs repair calls made
     * @param millis total LLM time
     */
    public record LlmInfo(String provider, String model, String promptVersion, int calls, int repairs, long millis,
            Integer promptTokens, Integer completionTokens) {
    }

    /** @param code {@code MALFORMED_MODEL_OUTPUT}, {@code LLM_UNAVAILABLE} or {@code INTERNAL} */
    public record Error(String code, String message) {
    }

    public IngestResult asReplay() {
        return new IngestResult(ingestionId, requestId, status, true, duplicateOf, dryRun, summary, opsProposed,
                opsApplied, opsRejected, accepted, rejected, adjusted, refs, planned, llm, error, receivedAt,
                completedAt, true);
    }

    public IngestResult asDuplicateOf(String earlierId) {
        return new IngestResult(ingestionId, requestId, status, false, earlierId, dryRun, summary, opsProposed,
                opsApplied, opsRejected, accepted, rejected, adjusted, refs, planned, llm, error, receivedAt,
                completedAt, true);
    }
}
