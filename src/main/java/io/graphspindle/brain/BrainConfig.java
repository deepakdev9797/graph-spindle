package io.graphspindle.brain;

import io.graphspindle.common.ConflictPolicy;
import io.graphspindle.common.Limits;
import io.graphspindle.ingestion.validation.ValidationPolicy;
import io.graphspindle.vocabulary.VocabularyConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-brain configuration (SCHEMA §5.4 {@code Brain.configJson}, §11). Missing fields take their
 * defaults, so a partial JSON document is a valid config. Immutable.
 *
 * @param conflictPolicy default {@code EXPLICIT_CORRECTIONS_SUPERSEDE}
 * @param minConfidence LLM claims below this are dropped; default 0.5
 * @param maxLlmConfidence LLM confidence is clamped to this; default 0.95
 * @param allowLlmSupersession whether explicit LLM corrections may supersede LLM claims; default true
 * @param retainRawContent store raw ingestion content (capped); default false (ADR-013)
 * @param rawContentMaxChars cap for retained raw content; default 32,000
 * @param dedupWindowHours window for content-hash deduplication; default 24
 * @param limits per-ingestion limits
 * @param vocabulary the brain's vocabulary subset and extensions; default core-v1 unchanged
 * @param duplicateSuspicionScore a new entity whose name is at least this similar ({@code NameMatch},
 *     0.5..1) to an existing compatible candidate opens a DUPLICATE_CANDIDATE conflict; default 0.85
 */
public record BrainConfig(
        ConflictPolicy conflictPolicy,
        Double minConfidence,
        Double maxLlmConfidence,
        Boolean allowLlmSupersession,
        Boolean retainRawContent,
        Integer rawContentMaxChars,
        Integer dedupWindowHours,
        BrainLimits limits,
        VocabularyConfig vocabulary,
        Double duplicateSuspicionScore) {

    /** Default {@code duplicateSuspicionScore} (ADR-030). */
    public static final double DEFAULT_DUPLICATE_SUSPICION_SCORE = 0.85;

    public BrainConfig(ConflictPolicy conflictPolicy, Double minConfidence, Double maxLlmConfidence,
            Boolean allowLlmSupersession, Boolean retainRawContent, Integer rawContentMaxChars,
            Integer dedupWindowHours, BrainLimits limits, VocabularyConfig vocabulary) {
        this(conflictPolicy, minConfidence, maxLlmConfidence, allowLlmSupersession, retainRawContent,
                rawContentMaxChars, dedupWindowHours, limits, vocabulary, null);
    }

    public BrainConfig {
        conflictPolicy = conflictPolicy == null ? ConflictPolicy.EXPLICIT_CORRECTIONS_SUPERSEDE : conflictPolicy;
        minConfidence = minConfidence == null ? 0.5 : minConfidence;
        maxLlmConfidence = maxLlmConfidence == null ? 0.95 : maxLlmConfidence;
        allowLlmSupersession = allowLlmSupersession == null || allowLlmSupersession;
        retainRawContent = retainRawContent != null && retainRawContent;
        rawContentMaxChars = rawContentMaxChars == null ? 32_000 : rawContentMaxChars;
        dedupWindowHours = dedupWindowHours == null ? 24 : dedupWindowHours;
        limits = limits == null ? BrainLimits.defaults() : limits;
        vocabulary = vocabulary == null ? OMITTED_VOCABULARY : vocabulary;
        duplicateSuspicionScore = duplicateSuspicionScore == null ? DEFAULT_DUPLICATE_SUSPICION_SCORE
                : duplicateSuspicionScore;
    }

    /**
     * The instance used when {@code vocabulary} is absent: equal to {@link VocabularyConfig#core()},
     * but recognisable by identity, so a PATCH without a vocabulary keeps the brain's (QA F7).
     */
    private static final VocabularyConfig OMITTED_VOCABULARY = VocabularyConfig.core();

    /** Whether this config was built without a vocabulary (it then carries the core default). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean vocabularyOmitted() {
        return vocabulary == OMITTED_VOCABULARY;
    }

    public static BrainConfig defaults() {
        return new BrainConfig(null, null, null, null, null, null, null, null, null, null);
    }

    public BrainConfig withVocabulary(VocabularyConfig v) {
        return new BrainConfig(conflictPolicy, minConfidence, maxLlmConfidence, allowLlmSupersession, retainRawContent,
                rawContentMaxChars, dedupWindowHours, limits, v, duplicateSuspicionScore);
    }

    /** Validation thresholds for {@code OperationValidator}. */
    public ValidationPolicy validationPolicy() {
        return new ValidationPolicy(minConfidence, maxLlmConfidence);
    }

    /** Problems with the scalar settings (the vocabulary is validated by {@code VocabularyRegistry}). */
    List<String> problems() {
        List<String> p = new ArrayList<>();
        if (!(minConfidence >= 0 && minConfidence <= 1)) {
            p.add("minConfidence must be within 0..1");
        }
        if (!(maxLlmConfidence > 0 && maxLlmConfidence <= 1)) {
            p.add("maxLlmConfidence must be within (0, 1]");
        }
        if (rawContentMaxChars < 0 || rawContentMaxChars > 100_000) {
            p.add("rawContentMaxChars must be within 0..100000");
        }
        if (!(duplicateSuspicionScore >= 0.5 && duplicateSuspicionScore <= 1.0)) {
            p.add("duplicateSuspicionScore must be within 0.5..1");
        }
        if (dedupWindowHours < 0 || dedupWindowHours > 720) {
            p.add("dedupWindowHours must be within 0..720");
        }
        p.addAll(limits.problems());
        return p;
    }

    /**
     * Per-ingestion limits a brain may set (ARCHITECTURE §8 defaults).
     */
    public record BrainLimits(Integer maxOperations, Integer maxNewEntities, Integer maxAliasesPerEntity,
            Integer maxClaimsPerSubject) {

        public BrainLimits {
            Limits d = Limits.defaults();
            maxOperations = maxOperations == null ? d.maxOperations() : maxOperations;
            maxNewEntities = maxNewEntities == null ? d.maxNewEntities() : maxNewEntities;
            maxAliasesPerEntity = maxAliasesPerEntity == null ? d.maxAliasesPerEntity() : maxAliasesPerEntity;
            maxClaimsPerSubject = maxClaimsPerSubject == null ? d.maxClaimsPerSubject() : maxClaimsPerSubject;
        }

        public static BrainLimits defaults() {
            return new BrainLimits(null, null, null, null);
        }

        List<String> problems() {
            List<String> p = new ArrayList<>();
            range(p, "limits.maxOperations", maxOperations, 1, 200);
            range(p, "limits.maxNewEntities", maxNewEntities, 0, 100);
            range(p, "limits.maxAliasesPerEntity", maxAliasesPerEntity, 0, 20);
            range(p, "limits.maxClaimsPerSubject", maxClaimsPerSubject, 1, 100);
            return p;
        }

        /** The validator limits with this brain's values applied. */
        public Limits toLimits() {
            Limits d = Limits.defaults();
            return new Limits(d.maxContentChars(), d.maxSources(), maxOperations, maxNewEntities, maxAliasesPerEntity,
                    maxClaimsPerSubject, d.maxModelOutputChars(), Math.max(maxOperations, d.maxParsedOperations()));
        }

        private static void range(List<String> p, String name, int v, int min, int max) {
            if (v < min || v > max) {
                p.add(name + " must be within " + min + ".." + max);
            }
        }
    }
}
