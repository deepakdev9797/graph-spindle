package io.graphspindle.graph;

/**
 * What the pipeline knows about an ingestion when it is applied.
 *
 * @param report JSON array of rejected operations (already truncated, untrusted strings bounded)
 */
public record IngestionCompletion(String summary, int opsProposed, int opsRejected, String report,
        String llmProvider, String llmModel, String promptVersion) {

    public static IngestionCompletion none(String summary) {
        return new IngestionCompletion(summary, 0, 0, null, null, null, null);
    }
}
