package io.graphspindle.vocabulary;

import static io.graphspindle.vocabulary.VocabularyIssue.Code.CANNOT_DISABLE;
import static io.graphspindle.vocabulary.VocabularyIssue.Code.DUPLICATE_NAME;
import static io.graphspindle.vocabulary.VocabularyIssue.Code.INVALID_NAME;
import static io.graphspindle.vocabulary.VocabularyIssue.Code.INVALID_VALUE;
import static io.graphspindle.vocabulary.VocabularyIssue.Code.LIMIT_EXCEEDED;
import static io.graphspindle.vocabulary.VocabularyIssue.Code.RESERVED_NAME;
import static io.graphspindle.vocabulary.VocabularyIssue.Code.UNKNOWN_PREDICATE;
import static io.graphspindle.vocabulary.VocabularyIssue.Code.UNKNOWN_PROFILE;
import static io.graphspindle.vocabulary.VocabularyIssue.Code.UNKNOWN_TYPE;

import io.graphspindle.vocabulary.VocabularyConfig.CustomAttribute;
import io.graphspindle.vocabulary.VocabularyConfig.CustomAttributePredicate;
import io.graphspindle.vocabulary.VocabularyConfig.CustomEntityType;
import io.graphspindle.vocabulary.VocabularyConfig.CustomRelationPredicate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Enforces the vocabulary extensibility bounds of SCHEMA §11 when a brain config is saved. */
public final class VocabularyConfigValidator {

    public static final int MAX_CUSTOM_ENTITY_TYPES = 20;
    public static final int MAX_CUSTOM_RELATIONS = 30;
    public static final int MAX_ATTRIBUTES_PER_TYPE = 10;
    public static final int MAX_ENUM_VALUES = 30;
    public static final int MAX_STRING_LENGTH = 500;
    public static final int MAX_URL_LENGTH = 2000;
    public static final int MAX_DESCRIPTION = 200;
    public static final int MAX_ENABLED_TYPES = 40;
    /** Raised from 60 for the richer core property schemas (ADR-025). */
    public static final int MAX_ENABLED_PREDICATES = 75;

    private static final int MAX_NAME_INPUT = 64;
    static final Pattern TYPE_NAME = Pattern.compile("^[A-Z][A-Za-z0-9]{1,39}$");
    static final Pattern RELATION_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    static final Pattern ATTRIBUTE_NAME = Pattern.compile("^[a-z][A-Za-z0-9]{1,39}$");
    private static final Pattern ENUM_VALUE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");

    private final CoreProfile core;

    /** A validator against the bundled core profile. */
    public VocabularyConfigValidator() {
        this(CoreVocabulary.DEFAULT);
    }

    public VocabularyConfigValidator(CoreProfile core) {
        this.core = java.util.Objects.requireNonNull(core, "core");
    }

    /** True when the name is a syntactically valid custom entity type name. */
    public static boolean isValidTypeName(String name) {
        return matches(TYPE_NAME, name);
    }

    /** True when the name is a syntactically valid custom relation predicate name. */
    public static boolean isValidRelationName(String name) {
        return matches(RELATION_NAME, name);
    }

    public static String typeNamePattern() {
        return TYPE_NAME.pattern();
    }

    public static String relationNamePattern() {
        return RELATION_NAME.pattern();
    }

    /** Returns all issues; empty means valid. */
    public List<VocabularyIssue> validate(VocabularyConfig config) {
        List<VocabularyIssue> issues = new ArrayList<>();
        if (config == null) {
            issues.add(new VocabularyIssue("$", INVALID_VALUE, "vocabulary configuration is required"));
            return issues;
        }
        if (!CoreVocabulary.PROFILE.equals(config.effectiveProfile())) {
            issues.add(new VocabularyIssue("profile", UNKNOWN_PROFILE, "only profile core-v1 is supported"));
        }

        Set<String> customTypeNames = validateCustomTypes(config.customEntityTypes(), issues);
        Set<String> enabledTypes = validateEnabledTypes(config, customTypeNames, issues);
        validateDisabledPredicates(config.disabledPredicates(), issues);
        Set<String> customRelationNames = validateCustomRelations(config.customRelationPredicates(), enabledTypes,
                issues);
        Set<String> customAttributeNames = validateAttributes(config, enabledTypes, issues);
        validateOverrides(config, customRelationNames, customAttributeNames, issues);
        Set<String> lowerRelations = new HashSet<>();
        customRelationNames.forEach(r -> lowerRelations.add(lower(r)));
        customAttributeNames.stream().filter(a -> lowerRelations.contains(lower(a))).sorted().forEach(a ->
                issues.add(new VocabularyIssue("customAttributePredicates", DUPLICATE_NAME, "attribute '" + a
                        + "' and a custom relation predicate differ only in case")));

        if (issues.isEmpty()) {
            BrainVocabulary resolved = VocabularyResolver.resolve(core, config);
            if (resolved.entityTypes().size() > MAX_ENABLED_TYPES) {
                issues.add(new VocabularyIssue("$", LIMIT_EXCEEDED,
                        "at most " + MAX_ENABLED_TYPES + " entity types may be enabled"));
            }
            if (resolved.predicateCount() > MAX_ENABLED_PREDICATES) {
                issues.add(new VocabularyIssue("$", LIMIT_EXCEEDED,
                        "at most " + MAX_ENABLED_PREDICATES + " predicates may be enabled, got "
                                + resolved.predicateCount()));
            }
        }
        return List.copyOf(issues);
    }

    private Set<String> validateCustomTypes(List<CustomEntityType> types, List<VocabularyIssue> issues) {
        Set<String> names = new HashSet<>();
        // canonicalKey lowercases the type, so type names are unique case-insensitively (ADR-021)
        Set<String> lowerNames = new HashSet<>();
        if (types.size() > MAX_CUSTOM_ENTITY_TYPES) {
            issues.add(new VocabularyIssue("customEntityTypes", LIMIT_EXCEEDED,
                    "at most " + MAX_CUSTOM_ENTITY_TYPES + " custom entity types"));
        }
        for (int i = 0; i < types.size(); i++) {
            CustomEntityType t = types.get(i);
            String path = "customEntityTypes[" + i + "]";
            if (t == null) {
                issues.add(new VocabularyIssue(path, INVALID_VALUE, "entry is null"));
                continue;
            }
            if (!matches(TYPE_NAME, t.name())) {
                issues.add(new VocabularyIssue(path + ".name", INVALID_NAME,
                        "type name must match " + TYPE_NAME.pattern()));
            } else if (CoreVocabulary.isReservedLabel(t.name()) || core.isCoreEntityTypeIgnoreCase(t.name())) {
                issues.add(new VocabularyIssue(path + ".name", RESERVED_NAME,
                        "'" + t.name() + "' is a reserved label or a core type (names are compared ignoring case)"));
            } else if (!lowerNames.add(lower(t.name()))) {
                issues.add(new VocabularyIssue(path + ".name", DUPLICATE_NAME, "duplicate type '" + t.name()
                        + "' (names are compared ignoring case)"));
            } else {
                names.add(t.name());
            }
            checkDescription(path + ".description", t.description(), issues);
            checkTexts(path + ".synonyms", t.synonyms(), VocabularyDefinitionLoader.MAX_SYNONYMS,
                    VocabularyDefinitionLoader.MAX_SYNONYM_LENGTH, issues);
            checkTexts(path + ".examples", t.examples(), VocabularyDefinitionLoader.MAX_EXAMPLES,
                    VocabularyDefinitionLoader.MAX_EXAMPLE_LENGTH, issues);
            if (t.compatibilityGroup() != null && !core.compatibilityGroups().containsKey(t.compatibilityGroup())) {
                issues.add(new VocabularyIssue(path + ".compatibilityGroup", INVALID_VALUE,
                        "compatibilityGroup must be null or one of " + core.compatibilityGroups().keySet()));
            }
        }
        return names;
    }

    private Set<String> validateEnabledTypes(VocabularyConfig config, Set<String> customTypes,
            List<VocabularyIssue> issues) {
        Set<String> enabled = new HashSet<>(customTypes);
        if (config.enabledEntityTypes() == null) {
            core.entityTypes().forEach(t -> enabled.add(t.name()));
            return enabled;
        }
        Set<String> seen = new HashSet<>();
        List<String> list = config.enabledEntityTypes();
        for (int i = 0; i < list.size(); i++) {
            String name = list.get(i);
            String path = "enabledEntityTypes[" + i + "]";
            if (name == null || !(core.isCoreEntityType(name) || customTypes.contains(name))) {
                issues.add(new VocabularyIssue(path, UNKNOWN_TYPE, "unknown entity type '" + safe(name) + "'"));
            } else if (!seen.add(name)) {
                issues.add(new VocabularyIssue(path, DUPLICATE_NAME, "duplicate entity type '" + name + "'"));
            } else {
                enabled.add(name);
            }
        }
        if (!seen.contains(CoreVocabulary.PERSON)) {
            issues.add(new VocabularyIssue("enabledEntityTypes", CANNOT_DISABLE,
                    "Person cannot be disabled (the brain's self entity is a Person)"));
        }
        return enabled;
    }

    private void validateDisabledPredicates(List<String> disabled, List<VocabularyIssue> issues) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < disabled.size(); i++) {
            String name = disabled.get(i);
            String path = "disabledPredicates[" + i + "]";
            if (name != null && (CoreVocabulary.SYSTEM_RELATIONSHIP_TYPES.contains(name)
                    || CoreVocabulary.isReservedLabel(name))) {
                issues.add(new VocabularyIssue(path, CANNOT_DISABLE, "system names cannot be disabled"));
            } else if (name == null || !(core.isCoreRelation(name) || core.isCoreAttributeName(name))) {
                issues.add(new VocabularyIssue(path, UNKNOWN_PREDICATE,
                        "unknown core predicate '" + safe(name) + "'"));
            } else if (!seen.add(name)) {
                issues.add(new VocabularyIssue(path, DUPLICATE_NAME, "duplicate predicate '" + name + "'"));
            }
        }
    }

    private Set<String> validateCustomRelations(List<CustomRelationPredicate> relations, Set<String> enabledTypes,
            List<VocabularyIssue> issues) {
        Set<String> names = new HashSet<>();
        Set<String> lowerNames = new HashSet<>();
        if (relations.size() > MAX_CUSTOM_RELATIONS) {
            issues.add(new VocabularyIssue("customRelationPredicates", LIMIT_EXCEEDED,
                    "at most " + MAX_CUSTOM_RELATIONS + " custom relation predicates"));
        }
        for (int i = 0; i < relations.size(); i++) {
            CustomRelationPredicate r = relations.get(i);
            String path = "customRelationPredicates[" + i + "]";
            if (r == null) {
                issues.add(new VocabularyIssue(path, INVALID_VALUE, "entry is null"));
                continue;
            }
            if (!matches(RELATION_NAME, r.name())) {
                issues.add(new VocabularyIssue(path + ".name", INVALID_NAME,
                        "relation name must match " + RELATION_NAME.pattern()));
            } else if (CoreVocabulary.SYSTEM_RELATIONSHIP_TYPES.stream().anyMatch(n -> n.equalsIgnoreCase(r.name()))
                    || core.isCorePredicateIgnoreCase(r.name())) {
                issues.add(new VocabularyIssue(path + ".name", RESERVED_NAME,
                        "'" + r.name() + "' is a system relationship type or a core predicate"));
            } else if (!lowerNames.add(lower(r.name()))) {
                issues.add(new VocabularyIssue(path + ".name", DUPLICATE_NAME,
                        "duplicate predicate '" + r.name() + "'"));
            } else {
                names.add(r.name());
            }
            checkDescription(path + ".description", r.description(), issues);
            checkTexts(path + ".synonyms", r.synonyms(), VocabularyDefinitionLoader.MAX_SYNONYMS,
                    VocabularyDefinitionLoader.MAX_SYNONYM_LENGTH, issues);
            checkTypes(path + ".sourceTypes", r.sourceTypes(), enabledTypes, issues);
            checkTypes(path + ".targetTypes", r.targetTypes(), enabledTypes, issues);
        }
        return names;
    }

    private Set<String> validateAttributes(VocabularyConfig config, Set<String> enabledTypes,
            List<VocabularyIssue> issues) {
        // existing attribute names per entity type, starting with the enabled core ones
        Map<String, Set<String>> namesByType = new HashMap<>();
        Set<String> disabled = Set.copyOf(config.disabledPredicates().stream().filter(java.util.Objects::nonNull).toList());
        for (AttributePredicateDef a : core.attributePredicates()) {
            if (!disabled.contains(a.name())) {
                a.entityTypes().forEach(t -> namesByType.computeIfAbsent(lower(t), k -> new HashSet<>())
                        .add(lower(a.name())));
            }
        }
        Map<String, Integer> customCountByType = new HashMap<>();
        Set<String> customNames = new HashSet<>();

        List<CustomEntityType> types = config.customEntityTypes();
        for (int i = 0; i < types.size(); i++) {
            CustomEntityType t = types.get(i);
            if (t == null) {
                continue;
            }
            for (int j = 0; j < t.attributes().size(); j++) {
                CustomAttribute a = t.attributes().get(j);
                String path = "customEntityTypes[" + i + "].attributes[" + j + "]";
                if (a == null) {
                    issues.add(new VocabularyIssue(path, INVALID_VALUE, "entry is null"));
                    continue;
                }
                checkAttribute(path, a.name(), a.valueType(), a.maxLength(), a.values(), a.min(), a.max(), issues);
                checkOptionalDescription(path + ".description", a.description(), issues);
                checkTexts(path + ".synonyms", a.synonyms(), VocabularyDefinitionLoader.MAX_SYNONYMS,
                        VocabularyDefinitionLoader.MAX_SYNONYM_LENGTH, issues);
                registerAttribute(path, a.name(), Set.of(String.valueOf(t.name())), namesByType, customCountByType,
                        issues);
                if (a.name() != null) {
                    customNames.add(a.name());
                }
            }
        }
        List<CustomAttributePredicate> preds = config.customAttributePredicates();
        for (int i = 0; i < preds.size(); i++) {
            CustomAttributePredicate a = preds.get(i);
            String path = "customAttributePredicates[" + i + "]";
            if (a == null) {
                issues.add(new VocabularyIssue(path, INVALID_VALUE, "entry is null"));
                continue;
            }
            checkAttribute(path, a.name(), a.valueType(), a.maxLength(), a.values(), a.min(), a.max(), issues);
            checkOptionalDescription(path + ".description", a.description(), issues);
            checkTexts(path + ".synonyms", a.synonyms(), VocabularyDefinitionLoader.MAX_SYNONYMS,
                    VocabularyDefinitionLoader.MAX_SYNONYM_LENGTH, issues);
            checkTypes(path + ".entityTypes", a.entityTypes(), enabledTypes, issues);
            registerAttribute(path, a.name(), Set.copyOf(a.entityTypes().stream()
                    .filter(java.util.Objects::nonNull).toList()), namesByType, customCountByType, issues);
            if (a.name() != null) {
                customNames.add(a.name());
            }
        }
        customCountByType.forEach((type, count) -> {
            if (count > MAX_ATTRIBUTES_PER_TYPE) {
                issues.add(new VocabularyIssue("attributes(" + type + ")", LIMIT_EXCEEDED,
                        "at most " + MAX_ATTRIBUTES_PER_TYPE + " custom attributes per entity type"));
            }
        });
        return customNames;
    }

    private void registerAttribute(String path, String name, Set<String> types, Map<String, Set<String>> namesByType,
            Map<String, Integer> customCountByType, List<VocabularyIssue> issues) {
        if (name == null) {
            return;
        }
        if (core.isCorePredicateIgnoreCase(name) && !core.isCoreAttributeName(name)) {
            issues.add(new VocabularyIssue(path + ".name", RESERVED_NAME,
                    "'" + name + "' differs from a core predicate only in case"));
        }
        for (String type : types) {
            // case-insensitive, so "homePage" cannot shadow the core "homepage"
            if (!namesByType.computeIfAbsent(lower(type), k -> new HashSet<>()).add(lower(name))) {
                issues.add(new VocabularyIssue(path + ".name", DUPLICATE_NAME,
                        "attribute '" + name + "' already exists for type " + type + " (names are compared ignoring case)"));
            }
            customCountByType.merge(type, 1, Integer::sum);
        }
    }

    static void checkAttribute(String path, String name, ValueType valueType, Integer maxLength, List<String> values,
            BigDecimal min, BigDecimal max, List<VocabularyIssue> issues) {
        if (!matches(ATTRIBUTE_NAME, name)) {
            issues.add(new VocabularyIssue(path + ".name", INVALID_NAME,
                    "attribute name must match " + ATTRIBUTE_NAME.pattern()));
        }
        if (valueType == null) {
            issues.add(new VocabularyIssue(path + ".valueType", INVALID_VALUE, "valueType is required"));
            return;
        }
        if (valueType == ValueType.ENUM) {
            if (values.isEmpty() || values.size() > MAX_ENUM_VALUES) {
                issues.add(new VocabularyIssue(path + ".values", values.isEmpty() ? INVALID_VALUE : LIMIT_EXCEEDED,
                        "ENUM needs 1 to " + MAX_ENUM_VALUES + " values"));
            }
            Set<String> seen = new HashSet<>();
            for (int k = 0; k < values.size(); k++) {
                String v = values.get(k);
                if (!matches(ENUM_VALUE, v)) {
                    issues.add(new VocabularyIssue(path + ".values[" + k + "]", INVALID_VALUE,
                            "enum value must match " + ENUM_VALUE.pattern()));
                } else if (!seen.add(v)) {
                    issues.add(new VocabularyIssue(path + ".values[" + k + "]", DUPLICATE_NAME,
                            "duplicate enum value '" + v + "'"));
                }
            }
        } else if (!values.isEmpty()) {
            issues.add(new VocabularyIssue(path + ".values", INVALID_VALUE, "values are only allowed for ENUM"));
        }
        if (maxLength != null) {
            int limit = valueType == ValueType.URL ? MAX_URL_LENGTH : MAX_STRING_LENGTH;
            if (valueType != ValueType.STRING && valueType != ValueType.URL) {
                issues.add(new VocabularyIssue(path + ".maxLength", INVALID_VALUE,
                        "maxLength is only allowed for STRING and URL"));
            } else if (maxLength < 1 || maxLength > limit) {
                issues.add(new VocabularyIssue(path + ".maxLength", INVALID_VALUE,
                        "maxLength must be between 1 and " + limit));
            }
        }
        if ((min != null || max != null) && valueType != ValueType.NUMBER) {
            issues.add(new VocabularyIssue(path, INVALID_VALUE, "min/max are only allowed for NUMBER"));
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            issues.add(new VocabularyIssue(path, INVALID_VALUE, "min must not exceed max"));
        }
    }

    private void validateOverrides(VocabularyConfig config, Set<String> customRelations, Set<String> customAttributes,
            List<VocabularyIssue> issues) {
        Set<String> disabled = new HashSet<>(config.disabledPredicates());
        for (Map.Entry<String, VocabularyConfig.PredicateOverride> e : new java.util.TreeMap<>(
                config.predicateOverrides()).entrySet()) {
            String name = e.getKey();
            String path = "predicateOverrides." + safe(name);
            boolean known = core.isCoreRelation(name) || core.isCoreAttributeName(name)
                    || customRelations.contains(name) || customAttributes.contains(name);
            if (!known || disabled.contains(name)) {
                issues.add(new VocabularyIssue(path, UNKNOWN_PREDICATE,
                        "override for unknown or disabled predicate '" + safe(name) + "'"));
            } else if (e.getValue() == null || e.getValue().cardinality() == null) {
                issues.add(new VocabularyIssue(path + ".cardinality", INVALID_VALUE, "cardinality is required"));
            }
        }
    }

    private static void checkTypes(String path, List<String> types, Set<String> enabledTypes,
            List<VocabularyIssue> issues) {
        if (types.isEmpty()) {
            issues.add(new VocabularyIssue(path, INVALID_VALUE, "at least one entity type is required"));
        }
        for (int i = 0; i < types.size(); i++) {
            if (!enabledTypes.contains(types.get(i))) {
                issues.add(new VocabularyIssue(path + "[" + i + "]", UNKNOWN_TYPE,
                        "entity type '" + safe(types.get(i)) + "' is not enabled"));
            }
        }
    }

    private static void checkDescription(String path, String description, List<VocabularyIssue> issues) {
        if (description == null || description.isBlank()) {
            issues.add(new VocabularyIssue(path, INVALID_VALUE, "description is required"));
        } else if (description.codePointCount(0, description.length()) > MAX_DESCRIPTION) {
            issues.add(new VocabularyIssue(path, INVALID_VALUE,
                    "description must be at most " + MAX_DESCRIPTION + " characters"));
        }
    }

    private static void checkOptionalDescription(String path, String description, List<VocabularyIssue> issues) {
        if (description != null) {
            checkDescription(path, description, issues);
        }
    }

    private static void checkTexts(String path, List<String> values, int maxCount, int maxLength,
            List<VocabularyIssue> issues) {
        List<String> problems = new ArrayList<>();
        VocabularyDefinitionLoader.texts(path, values, maxCount, maxLength, problems);
        problems.forEach(p -> issues.add(new VocabularyIssue(path, values != null && values.size() > maxCount
                ? LIMIT_EXCEEDED : INVALID_VALUE, p)));
    }

    private static String lower(String s) {
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    static boolean matches(Pattern p, String s) {
        return s != null && s.length() <= MAX_NAME_INPUT && p.matcher(s).matches();
    }

    private static String safe(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
