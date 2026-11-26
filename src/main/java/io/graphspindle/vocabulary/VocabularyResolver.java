package io.graphspindle.vocabulary;

import io.graphspindle.vocabulary.VocabularyConfig.CustomAttribute;
import io.graphspindle.vocabulary.VocabularyConfig.CustomAttributePredicate;
import io.graphspindle.vocabulary.VocabularyConfig.CustomEntityType;
import io.graphspindle.vocabulary.VocabularyConfig.CustomRelationPredicate;
import io.graphspindle.vocabulary.VocabularyConfig.PredicateOverride;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a (validated) {@link VocabularyConfig} into a {@link BrainVocabulary}. Tolerant: it never
 * throws on bad input, it skips what it cannot use. Validation is the validator's job.
 */
final class VocabularyResolver {

    static final int DEFAULT_STRING_MAX = 200;
    static final int DEFAULT_URL_MAX = 2000;

    private VocabularyResolver() {
    }

    static BrainVocabulary resolve(VocabularyConfig config) {
        Set<String> enabledCore = config.enabledEntityTypes() == null ? null : Set.copyOf(config.enabledEntityTypes());
        List<EntityTypeDef> types = new ArrayList<>();
        for (EntityTypeDef t : CoreVocabulary.ENTITY_TYPES) {
            if (enabledCore == null || enabledCore.contains(t.name())) {
                types.add(t);
            }
        }
        for (CustomEntityType c : config.customEntityTypes()) {
            if (c.name() != null && types.stream().noneMatch(t -> t.name().equals(c.name()))) {
                types.add(new EntityTypeDef(c.name(), c.description() == null ? c.name() : c.description(),
                        List.of(), c.compatibilityGroup(), false));
            }
        }
        Set<String> enabled = new LinkedHashSet<>();
        types.forEach(t -> enabled.add(t.name()));
        Set<String> disabled = Set.copyOf(config.disabledPredicates());
        Map<String, PredicateOverride> overrides = config.predicateOverrides();

        List<RelationPredicateDef> relations = new ArrayList<>();
        for (RelationPredicateDef p : CoreVocabulary.RELATION_PREDICATES) {
            if (!disabled.contains(p.name())) {
                addRelation(relations, override(p, overrides), enabled);
            }
        }
        for (CustomRelationPredicate c : config.customRelationPredicates()) {
            if (c.name() == null || relations.stream().anyMatch(r -> r.name().equals(c.name()))) {
                continue;
            }
            RelationPredicateDef def = RelationPredicateDef.simple(c.name(),
                    c.description() == null ? c.name() : c.description(), TypeSet.of(c.sourceTypes()),
                    TypeSet.of(c.targetTypes()), c.cardinality() == null ? Cardinality.MANY : c.cardinality(), false);
            addRelation(relations, override(def, overrides), enabled);
        }

        List<AttributePredicateDef> attributes = new ArrayList<>();
        for (AttributePredicateDef a : CoreVocabulary.ATTRIBUTE_PREDICATES) {
            if (!disabled.contains(a.name())) {
                AttributePredicateDef restricted = override(a, overrides).restrictTo(enabled);
                if (!restricted.entityTypes().isEmpty()) {
                    attributes.add(restricted);
                }
            }
        }
        for (CustomEntityType c : config.customEntityTypes()) {
            for (CustomAttribute a : c.attributes()) {
                if (a.name() != null && a.valueType() != null) {
                    attributes.add(override(custom(a.name(), Set.of(c.name()), a.valueType(), a.maxLength(),
                            a.values(), a), overrides));
                }
            }
        }
        for (CustomAttributePredicate a : config.customAttributePredicates()) {
            if (a.name() == null || a.valueType() == null) {
                continue;
            }
            AttributePredicateDef def = override(custom(a.name(), Set.copyOf(a.entityTypes()), a.valueType(),
                    a.maxLength(), a.values(), null), overrides).restrictTo(enabled);
            if (!def.entityTypes().isEmpty()) {
                attributes.add(new AttributePredicateDef(def.name(), def.description(), def.entityTypes(),
                        def.valueType(), def.enumValues(), def.maxLength(), a.min(), a.max(), def.cardinality(),
                        Set.of(), false));
            }
        }
        return new BrainVocabulary(config.effectiveProfile(), types, relations, attributes);
    }

    private static void addRelation(List<RelationPredicateDef> out, RelationPredicateDef def, Set<String> enabled) {
        RelationPredicateDef restricted = def.restrictTo(enabled);
        if (restricted.isUsable()) {
            out.add(restricted);
        }
    }

    private static RelationPredicateDef override(RelationPredicateDef def, Map<String, PredicateOverride> overrides) {
        PredicateOverride o = overrides.get(def.name());
        return o == null || o.cardinality() == null ? def : def.withCardinality(o.cardinality());
    }

    private static AttributePredicateDef override(AttributePredicateDef def, Map<String, PredicateOverride> overrides) {
        PredicateOverride o = overrides.get(def.name());
        return o == null || o.cardinality() == null ? def : def.withCardinality(o.cardinality());
    }

    private static AttributePredicateDef custom(String name, Set<String> types, ValueType valueType, Integer maxLength,
            List<String> values, CustomAttribute inline) {
        Integer max = maxLength;
        if (max == null && valueType == ValueType.STRING) {
            max = DEFAULT_STRING_MAX;
        }
        if (valueType == ValueType.URL) {
            max = max == null ? DEFAULT_URL_MAX : Math.min(max, DEFAULT_URL_MAX);
        }
        return new AttributePredicateDef(name, "custom attribute " + name, types, valueType,
                valueType == ValueType.ENUM ? values : List.of(), max, inline == null ? null : inline.min(),
                inline == null ? null : inline.max(), Cardinality.ONE, Set.of(), false);
    }
}
