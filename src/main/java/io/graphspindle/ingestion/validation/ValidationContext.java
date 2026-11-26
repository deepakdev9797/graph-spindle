package io.graphspindle.ingestion.validation;

import io.graphspindle.common.Limits;
import io.graphspindle.vocabulary.BrainVocabulary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Everything {@link OperationValidator} needs, as plain immutable values: no I/O happens during
 * validation.
 *
 * @param brainId the brain (from the authenticated request, never from content or LLM output)
 * @param vocabulary the brain's resolved vocabulary
 * @param candidates the stage-2 candidate set
 * @param content the redacted ingestion content, used for evidence checks
 * @param sourceSummaries one entry per request source (null entries allowed); its size bounds source indexes
 * @param limits count and size limits
 * @param policy confidence thresholds
 * @param secretScanner secret patterns of the brain
 */
public record ValidationContext(String brainId, BrainVocabulary vocabulary, CandidateSet candidates, String content,
        List<String> sourceSummaries, Limits limits, ValidationPolicy policy, SecretScanner secretScanner) {

    public ValidationContext {
        Objects.requireNonNull(brainId, "brainId");
        Objects.requireNonNull(vocabulary, "vocabulary");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(content, "content");
        sourceSummaries = Collections.unmodifiableList(new ArrayList<>(sourceSummaries == null ? List.of()
                : sourceSummaries));
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(secretScanner, "secretScanner");
    }

    /** Context with default limits, policy and secret patterns. */
    public static ValidationContext of(String brainId, BrainVocabulary vocabulary, CandidateSet candidates,
            String content, List<String> sourceSummaries) {
        return new ValidationContext(brainId, vocabulary, candidates, content, sourceSummaries, Limits.defaults(),
                ValidationPolicy.defaults(), new SecretScanner());
    }

    public ValidationContext withLimits(Limits l) {
        return new ValidationContext(brainId, vocabulary, candidates, content, sourceSummaries, l, policy,
                secretScanner);
    }

    public ValidationContext withPolicy(ValidationPolicy p) {
        return new ValidationContext(brainId, vocabulary, candidates, content, sourceSummaries, limits, p,
                secretScanner);
    }
}
