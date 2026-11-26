package io.graphspindle.common;

/**
 * Request and model-output limits (ARCHITECTURE §8 defaults). Configurable later through
 * properties or brain configuration; this record only carries the values.
 *
 * @param maxContentChars maximum ingestion content length
 * @param maxSources maximum source references per request
 * @param maxOperations maximum operations accepted per proposal; extra ones are rejected
 * @param maxNewEntities maximum new entities per ingestion
 * @param maxAliasesPerEntity maximum aliases added to one entity per ingestion
 * @param maxClaimsPerSubject maximum claims about one subject per ingestion
 * @param maxModelOutputChars raw model output above this size is refused outright
 * @param maxParsedOperations hard cap on the operations array; above it the output is malformed
 */
public record Limits(
        int maxContentChars,
        int maxSources,
        int maxOperations,
        int maxNewEntities,
        int maxAliasesPerEntity,
        int maxClaimsPerSubject,
        int maxModelOutputChars,
        int maxParsedOperations) {

    public Limits {
        if (maxContentChars <= 0 || maxSources < 0 || maxOperations <= 0 || maxNewEntities < 0
                || maxAliasesPerEntity < 0 || maxClaimsPerSubject <= 0 || maxModelOutputChars <= 0
                || maxParsedOperations < maxOperations) {
            throw new IllegalArgumentException("invalid limits");
        }
    }

    public static Limits defaults() {
        return new Limits(32_000, 10, 60, 25, 5, 30, 2_000_000, 500);
    }

    public Limits withMaxOperations(int ops) {
        return new Limits(maxContentChars, maxSources, ops, maxNewEntities, maxAliasesPerEntity,
                maxClaimsPerSubject, maxModelOutputChars, Math.max(ops, maxParsedOperations));
    }

    public Limits withMaxNewEntities(int n) {
        return new Limits(maxContentChars, maxSources, maxOperations, n, maxAliasesPerEntity,
                maxClaimsPerSubject, maxModelOutputChars, maxParsedOperations);
    }

    public Limits withMaxClaimsPerSubject(int n) {
        return new Limits(maxContentChars, maxSources, maxOperations, maxNewEntities, maxAliasesPerEntity,
                n, maxModelOutputChars, maxParsedOperations);
    }
}
