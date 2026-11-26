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

    private final CoreProfile profile;
    private final VocabularyConfigValidator validator;
    private final BrainVocabulary core;

    /** A registry for the bundled core profile. */
    public VocabularyRegistry() {
        this(CoreVocabulary.DEFAULT);
    }

    public VocabularyRegistry(CoreProfile profile) {
        this.profile = java.util.Objects.requireNonNull(profile, "profile");
        this.validator = new VocabularyConfigValidator(profile);
        this.core = VocabularyResolver.resolve(profile, VocabularyConfig.core());
    }

    /** The core profile (bundled YAML plus any operator override). */
    public CoreProfile profile() {
        return profile;
    }

    /** Validates without resolving; empty means valid. */
    public List<VocabularyIssue> validate(VocabularyConfig config) {
        return validator.validate(config);
    }

    /** Resolves without validating (tolerant), for views of hypothetical configurations. */
    public BrainVocabulary resolveUnchecked(VocabularyConfig config) {
        return VocabularyResolver.resolve(profile, config == null ? VocabularyConfig.core() : config);
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
        return VocabularyResolver.resolve(profile, config);
    }
}
