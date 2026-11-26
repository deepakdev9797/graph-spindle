package io.graphspindle.vocabulary;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The per-brain vocabulary configuration ({@code BrainConfig.vocabulary}, SCHEMA §11). This is
 * admin input: it is validated by {@link VocabularyConfigValidator} before it is resolved.
 *
 * @param profile the core profile, {@code core-v1}; null means {@code core-v1}
 * @param enabledEntityTypes core types to enable; null means all core types
 * @param disabledPredicates core relation or attribute predicate names to remove
 * @param predicateOverrides cardinality overrides by predicate name
 * @param customEntityTypes brain-specific entity types
 * @param customRelationPredicates brain-specific relation predicates
 * @param customAttributePredicates brain-specific attribute predicates
 */
public record VocabularyConfig(
        String profile,
        List<String> enabledEntityTypes,
        List<String> disabledPredicates,
        Map<String, PredicateOverride> predicateOverrides,
        List<CustomEntityType> customEntityTypes,
        List<CustomRelationPredicate> customRelationPredicates,
        List<CustomAttributePredicate> customAttributePredicates) {

    public VocabularyConfig {
        enabledEntityTypes = enabledEntityTypes == null ? null : List.copyOf(enabledEntityTypes);
        disabledPredicates = disabledPredicates == null ? List.of() : List.copyOf(disabledPredicates);
        predicateOverrides = predicateOverrides == null ? Map.of() : Map.copyOf(predicateOverrides);
        customEntityTypes = customEntityTypes == null ? List.of() : List.copyOf(customEntityTypes);
        customRelationPredicates = customRelationPredicates == null ? List.of() : List.copyOf(customRelationPredicates);
        customAttributePredicates =
                customAttributePredicates == null ? List.of() : List.copyOf(customAttributePredicates);
    }

    /** The default: the full core profile with no changes. */
    public static VocabularyConfig core() {
        return new VocabularyConfig(CoreVocabulary.PROFILE, null, List.of(), Map.of(), List.of(), List.of(), List.of());
    }

    public String effectiveProfile() {
        return profile == null ? CoreVocabulary.PROFILE : profile;
    }

    /** Only the cardinality of a predicate may be overridden. */
    public record PredicateOverride(Cardinality cardinality) {
    }

    /**
     * @param name upper-camel label
     * @param description one line for the LLM (≤ 200)
     * @param compatibilityGroup one of the core groups, or null
     * @param attributes attribute predicates that apply to this type only
     * @param synonyms alternative names (≤ 10, each ≤ 60), rendered into the prompt
     * @param examples short examples (≤ 5, each ≤ 100), rendered into the prompt
     */
    public record CustomEntityType(String name, String description, String compatibilityGroup,
            List<CustomAttribute> attributes, List<String> synonyms, List<String> examples) {

        public CustomEntityType {
            attributes = attributes == null ? List.of() : List.copyOf(attributes);
            synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
            examples = examples == null ? List.of() : List.copyOf(examples);
        }

        public CustomEntityType(String name, String description, String compatibilityGroup,
                List<CustomAttribute> attributes) {
            this(name, description, compatibilityGroup, attributes, null, null);
        }
    }

    /**
     * An attribute declared inline on a custom entity type. {@code description}, {@code cardinality}
     * (default ONE), {@code required} (advisory, default false) and {@code synonyms} are optional.
     */
    public record CustomAttribute(String name, ValueType valueType, Integer maxLength, List<String> values,
            BigDecimal min, BigDecimal max, String description, Cardinality cardinality, Boolean required,
            List<String> synonyms) {

        public CustomAttribute {
            values = values == null ? List.of() : List.copyOf(values);
            synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
        }

        public CustomAttribute(String name, ValueType valueType, Integer maxLength, List<String> values,
                BigDecimal min, BigDecimal max) {
            this(name, valueType, maxLength, values, min, max, null, null, null, null);
        }
    }

    public record CustomRelationPredicate(String name, String description, List<String> sourceTypes,
            List<String> targetTypes, Cardinality cardinality, List<String> synonyms) {

        public CustomRelationPredicate {
            sourceTypes = sourceTypes == null ? List.of() : List.copyOf(sourceTypes);
            targetTypes = targetTypes == null ? List.of() : List.copyOf(targetTypes);
            synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
        }

        public CustomRelationPredicate(String name, String description, List<String> sourceTypes,
                List<String> targetTypes, Cardinality cardinality) {
            this(name, description, sourceTypes, targetTypes, cardinality, null);
        }
    }

    /** An attribute for one or more (core or custom) entity types; optional fields as in {@link CustomAttribute}. */
    public record CustomAttributePredicate(String name, List<String> entityTypes, ValueType valueType,
            Integer maxLength, List<String> values, BigDecimal min, BigDecimal max, String description,
            Cardinality cardinality, Boolean required, List<String> synonyms) {

        public CustomAttributePredicate {
            entityTypes = entityTypes == null ? List.of() : List.copyOf(entityTypes);
            values = values == null ? List.of() : List.copyOf(values);
            synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
        }

        public CustomAttributePredicate(String name, List<String> entityTypes, ValueType valueType,
                Integer maxLength, List<String> values, BigDecimal min, BigDecimal max) {
            this(name, entityTypes, valueType, maxLength, values, min, max, null, null, null, null);
        }
    }

    /** A copy with other custom entity types. */
    public VocabularyConfig withCustomEntityTypes(List<CustomEntityType> types) {
        return new VocabularyConfig(profile, enabledEntityTypes, disabledPredicates, predicateOverrides, types,
                customRelationPredicates, customAttributePredicates);
    }

    public VocabularyConfig withCustomRelationPredicates(List<CustomRelationPredicate> relations) {
        return new VocabularyConfig(profile, enabledEntityTypes, disabledPredicates, predicateOverrides,
                customEntityTypes, relations, customAttributePredicates);
    }

    public VocabularyConfig withCustomAttributePredicates(List<CustomAttributePredicate> attributes) {
        return new VocabularyConfig(profile, enabledEntityTypes, disabledPredicates, predicateOverrides,
                customEntityTypes, customRelationPredicates, attributes);
    }

    public VocabularyConfig withEnabledEntityTypes(List<String> enabled) {
        return new VocabularyConfig(profile, enabled, disabledPredicates, predicateOverrides, customEntityTypes,
                customRelationPredicates, customAttributePredicates);
    }

    public VocabularyConfig withDisabledPredicates(List<String> disabled) {
        return new VocabularyConfig(profile, enabledEntityTypes, disabled, predicateOverrides, customEntityTypes,
                customRelationPredicates, customAttributePredicates);
    }

    /** The same configuration with {@code profile} made explicit, for equality checks. */
    public VocabularyConfig normalized() {
        return new VocabularyConfig(effectiveProfile(), enabledEntityTypes, disabledPredicates, predicateOverrides,
                customEntityTypes, customRelationPredicates, customAttributePredicates);
    }
}
