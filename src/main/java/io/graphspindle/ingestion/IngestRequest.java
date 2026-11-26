package io.graphspindle.ingestion;

import java.time.Instant;
import java.util.List;

/**
 * An ingestion submission (ARCHITECTURE §3 stage 0). {@code content} is untrusted text; sources are
 * references only and are never opened or fetched (ADR-013).
 *
 * @param content the text to remember (≤ 32,000 characters)
 * @param contentType {@code CONVERSATION | NOTE | OBSERVATION | DOCUMENT_SUMMARY | OTHER}; default NOTE
 * @param requestId client idempotency key (≤ 100 characters of {@code [A-Za-z0-9._:-]}); generated when absent
 * @param sources up to 10 source references
 * @param speaker who wrote the content; null means the brain owner (the self entity)
 * @param occurredAt when the original event happened, if known
 */
public record IngestRequest(String content, String contentType, String requestId, List<Source> sources,
        Speaker speaker, Instant occurredAt) {

    public IngestRequest {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** A reference to where the knowledge came from (SCHEMA §5.4 SourceRef); never dereferenced. */
    public record Source(String kind, String locator, String title, String summary, String mediaType,
            String contentHash) {
    }

    /** The author of the content: a Person or an Agent of this brain (resolved by name). */
    public record Speaker(String type, String name) {
    }
}
