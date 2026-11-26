package io.graphspindle.ingestion.validation;

/**
 * Brain-level validation thresholds (from {@code BrainConfig}).
 *
 * @param minConfidence claims below this are dropped ({@code LOW_CONFIDENCE}); default 0.5
 * @param maxLlmConfidence LLM confidence is clamped to this; default 0.95
 */
public record ValidationPolicy(double minConfidence, double maxLlmConfidence) {

    public ValidationPolicy {
        if (!(minConfidence >= 0 && minConfidence <= 1 && maxLlmConfidence > 0 && maxLlmConfidence <= 1)) {
            throw new IllegalArgumentException("confidence thresholds must be within 0..1");
        }
    }

    public static ValidationPolicy defaults() {
        return new ValidationPolicy(0.5, 0.95);
    }
}
