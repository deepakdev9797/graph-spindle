package io.graphspindle.vocabulary;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The core vocabulary profile as loaded and validated from YAML ({@link VocabularyDefinitionLoader},
 * ADR-025). Immutable. Every brain's vocabulary is a subset of this profile plus its custom additions.
 */
public final class CoreProfile {

    private final String name;
    private final String source;
    private final List<EntityTypeDef> entityTypes;
    private final List<RelationPredicateDef> relations;
    private final List<AttributePredicateDef> attributes;
    private final List<ClassificationHint> hints;
    private final Map<String, Set<String>> groups;

    CoreProfile(String name, String source, List<EntityTypeDef> entityTypes, List<RelationPredicateDef> relations,
            List<AttributePredicateDef> attributes, List<ClassificationHint> hints) {
        this.name = Objects.requireNonNull(name);
        this.source = source;
        this.entityTypes = List.copyOf(entityTypes);
        this.relations = List.copyOf(relations);
        this.attributes = List.copyOf(attributes);
        this.hints = List.copyOf(hints);
        LinkedHashMap<String, Set<String>> g = new LinkedHashMap<>();
        for (EntityTypeDef t : entityTypes) {
            if (t.compatibilityGroup() != null) {
                g.computeIfAbsent(t.compatibilityGroup(), k -> new TreeSet<>()).add(t.name());
            }
        }
        g.replaceAll((k, v) -> Collections.unmodifiableSet(v));
        this.groups = Collections.unmodifiableMap(g);
    }

    /** The profile name, {@code core-v1}. */
    public String name() {
        return name;
    }

    /** Where the definition was loaded from (for messages), e.g. {@code classpath:vocabulary/core-v1.yml}. */
    public String source() {
        return source;
    }

    public List<EntityTypeDef> entityTypes() {
        return entityTypes;
    }

    public List<RelationPredicateDef> relationPredicates() {
        return relations;
    }

    public List<AttributePredicateDef> attributePredicates() {
        return attributes;
    }

    public List<ClassificationHint> classificationHints() {
        return hints;
    }

    /** Type compatibility groups derived from the types' {@code group} (SCHEMA §6.2). */
    public Map<String, Set<String>> compatibilityGroups() {
        return groups;
    }

    public Optional<EntityTypeDef> entityType(String type) {
        return entityTypes.stream().filter(t -> t.name().equals(type)).findFirst();
    }

    public Optional<RelationPredicateDef> relation(String predicate) {
        return relations.stream().filter(p -> p.name().equals(predicate)).findFirst();
    }

    /** The core attribute definitions that apply to the entity type. */
    public List<AttributePredicateDef> attributesOf(String type) {
        return attributes.stream().filter(a -> a.entityTypes().contains(type)).toList();
    }

    public boolean isCoreEntityType(String type) {
        return entityTypes.stream().anyMatch(t -> t.name().equals(type));
    }

    public boolean isCoreEntityTypeIgnoreCase(String type) {
        return type != null && entityTypes.stream().anyMatch(t -> t.name().equalsIgnoreCase(type));
    }

    public boolean isCoreRelation(String predicate) {
        return relations.stream().anyMatch(p -> p.name().equals(predicate));
    }

    public boolean isCoreAttributeName(String predicate) {
        return attributes.stream().anyMatch(p -> p.name().equals(predicate));
    }

    public boolean isCorePredicateIgnoreCase(String predicate) {
        return predicate != null && (relations.stream().anyMatch(p -> p.name().equalsIgnoreCase(predicate))
                || attributes.stream().anyMatch(p -> p.name().equalsIgnoreCase(predicate)));
    }

    /** Distinct attribute predicate names (sorted). */
    public Set<String> attributeNames() {
        TreeSet<String> names = new TreeSet<>();
        attributes.forEach(a -> names.add(a.name()));
        return Collections.unmodifiableSortedSet(names);
    }

    public String groupOf(String type) {
        return entityType(type).map(EntityTypeDef::compatibilityGroup).orElse(null);
    }

    @Override
    public String toString() {
        return "CoreProfile[" + name + " from " + source + ": " + entityTypes.size() + " types, " + relations.size()
                + " relations, " + attributeNames().size() + " attribute names]";
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
