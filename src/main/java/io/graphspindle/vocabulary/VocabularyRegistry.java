package io.graphspindle.vocabulary;

import java.util.List;

/**
 * Entry point for resolved vocabularies. {@link #forBrain(VocabularyConfig)} validates the brain's
 * vocabulary configuration and resolves it against the core profile.
 *
 * <p>The brain-id based lookup used by {@code GET /brains/{id}/vocabulary} needs {@code BrainService}
 * (M2) and is added on top of this method there.
 */
public final class VocabularyRegistry {

    private final VocabularyConfigValidator validator;
    private final BrainVocabulary core;

    public VocabularyRegistry() {
        this(new VocabularyConfigValidator());
    }

    public VocabularyRegistry(VocabularyConfigValidator validator) {
        this.validator = validator;
        this.core = VocabularyResolver.resolve(VocabularyConfig.core());
    }

    /** The full core profile. */
    public BrainVocabulary core() {
        return core;
    }

    /**
     * Resolves a brain's vocabulary (enabled subset plus bounded custom types and predicates).
     *
     * @throws InvalidVocabularyConfigException if the configuration violates SCHEMA §11
     */
    public BrainVocabulary forBrain(VocabularyConfig config) {
        if (config == null) {
            return core;
        }
        List<VocabularyIssue> issues = validator.validate(config);
        if (!issues.isEmpty()) {
            throw new InvalidVocabularyConfigException(issues);
        }
        return VocabularyResolver.resolve(config);
    }
}
