package io.graphspindle.vocabulary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The vocabulary resolved for one brain: the enabled subset of the core profile plus the brain's
 * custom types and predicates. Immutable. Iteration order is deterministic (core order, then
 * custom order), so renderings are stable.
 */
public final class BrainVocabulary {

    private final CoreProfile core;
    private final String profile;
    private final Map<String, EntityTypeDef> types;
    private final Map<String, RelationPredicateDef> relations;
    private final List<AttributePredicateDef> attributes;
    private final Map<String, List<AttributePredicateDef>> attributesByName;

    BrainVocabulary(CoreProfile core, String profile, List<EntityTypeDef> types, List<RelationPredicateDef> relations,
            List<AttributePredicateDef> attributes) {
        this.core = Objects.requireNonNull(core);
        this.profile = Objects.requireNonNull(profile);
        LinkedHashMap<String, EntityTypeDef> t = new LinkedHashMap<>();
        types.forEach(d -> t.put(d.name(), d));
        this.types = Collections.unmodifiableMap(t);
        LinkedHashMap<String, RelationPredicateDef> r = new LinkedHashMap<>();
        relations.forEach(d -> r.put(d.name(), d));
        this.relations = Collections.unmodifiableMap(r);
        this.attributes = List.copyOf(attributes);
        LinkedHashMap<String, List<AttributePredicateDef>> byName = new LinkedHashMap<>();
        attributes.forEach(a -> byName.computeIfAbsent(a.name(), k -> new ArrayList<>()).add(a));
        byName.replaceAll((k, v) -> List.copyOf(v));
        this.attributesByName = Collections.unmodifiableMap(byName);
    }

    public String profile() {
        return profile;
    }

    public List<EntityTypeDef> entityTypes() {
        return List.copyOf(types.values());
    }

    public Set<String> entityTypeNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(types.keySet()));
    }

    public Optional<EntityTypeDef> entityType(String name) {
        return Optional.ofNullable(name == null ? null : types.get(name));
    }

    public boolean isEnabledType(String name) {
        return name != null && types.containsKey(name);
    }

    /**
     * The enabled type a free-form type word names: the type itself, its name in another case, or a
     * type whose synonym it is (normalized like {@link NameSimilarity#normalize}). Used to read the
     * extraction's mention types leniently (ADR-030); empty when nothing matches unambiguously.
     */
    public Optional<String> typeFor(String word) {
        if (word == null || word.isBlank()) {
            return Optional.empty();
        }
        if (isEnabledType(word)) {
            return Optional.of(word);
        }
        String n = NameSimilarity.normalize(word);
        java.util.Set<String> matches = new java.util.TreeSet<>();
        for (EntityTypeDef t : entityTypes()) {
            if (NameSimilarity.normalize(t.name()).equals(n)) {
                return Optional.of(t.name());
            }
            if (t.synonyms().stream().anyMatch(s -> NameSimilarity.normalize(s).equals(n))) {
                matches.add(t.name());
            }
        }
        return matches.size() == 1 ? Optional.of(matches.iterator().next()) : Optional.empty();
    }

    public List<RelationPredicateDef> relationPredicates() {
        return List.copyOf(relations.values());
    }

    public Optional<RelationPredicateDef> relation(String name) {
        return Optional.ofNullable(name == null ? null : relations.get(name));
    }

    public List<AttributePredicateDef> attributePredicates() {
        return attributes;
    }

    /** Distinct attribute predicate names, in definition order. */
    public Set<String> attributeNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(attributesByName.keySet()));
    }

    public boolean hasAttributeName(String name) {
        return name != null && attributesByName.containsKey(name);
    }

    /** The attribute definition with that name applying to the entity type, if any. */
    public Optional<AttributePredicateDef> attribute(String name, String entityType) {
        if (name == null) {
            return Optional.empty();
        }
        return attributesByName.getOrDefault(name, List.of()).stream()
                .filter(a -> a.entityTypes().contains(entityType)).findFirst();
    }

    /** True if the relation predicate is enabled and allows the (enabled) type pair. */
    public boolean allowsRelation(String predicate, String sourceType, String targetType) {
        return isEnabledType(sourceType) && isEnabledType(targetType)
                && relation(predicate).map(p -> p.allows(sourceType, targetType)).orElse(false);
    }

    /**
     * Type compatibility for entity resolution (SCHEMA §6.2): identical types, or both types in the
     * same compatibility group.
     */
    public boolean compatible(String typeA, String typeB) {
        if (typeA == null || typeB == null) {
            return false;
        }
        if (typeA.equals(typeB)) {
            return true;
        }
        String ga = types.containsKey(typeA) ? types.get(typeA).compatibilityGroup() : groupOfCore(typeA);
        String gb = types.containsKey(typeB) ? types.get(typeB).compatibilityGroup() : groupOfCore(typeB);
        return ga != null && ga.equals(gb);
    }

    /** Number of enabled predicates: relation predicates plus distinct attribute names. */
    public int predicateCount() {
        return relations.size() + attributesByName.size();
    }

    private String groupOfCore(String type) {
        return core.groupOf(type);
    }

    /** The core profile this vocabulary was resolved against. */
    public CoreProfile core() {
        return core;
    }

    /** Classification hints whose required type is enabled. */
    public List<ClassificationHint> classificationHints() {
        return core.classificationHints().stream()
                .filter(h -> h.requiresType() == null || isEnabledType(h.requiresType())).toList();
    }

    /** The enabled attribute definitions that apply to the entity type, in definition order. */
    public List<AttributePredicateDef> attributesOf(String entityType) {
        return attributes.stream().filter(a -> a.entityTypes().contains(entityType)).toList();
    }

    @Override
    public String toString() {
        return "BrainVocabulary[" + profile + ", types=" + types.keySet() + ", relations=" + relations.keySet()
                + ", attributes=" + attributesByName.keySet() + "]";
    }
}
