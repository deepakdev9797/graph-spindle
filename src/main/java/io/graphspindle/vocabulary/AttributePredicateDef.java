package io.graphspindle.vocabulary;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An attribute predicate (literal claim projected as {@code attr_<name>}, SCHEMA §6.4). Several
 * definitions may share a name when the allowed values differ by entity type ({@code status}).
 *
 * @param name lower-camel name
 * @param description meaning given to the LLM
 * @param entityTypes subject types this definition applies to
 * @param valueType literal type
 * @param enumValues allowed values for ENUM (upper-snake), else empty
 * @param maxLength maximum length for STRING and URL, else null
 * @param min minimum for NUMBER, or null
 * @param max maximum for NUMBER, or null
 * @param cardinality ONE (default) or MANY
 * @param engineOnlyValues enum values only the engine may set (e.g. Decision {@code SUPERSEDED})
 * @param core true when it comes from the core profile
 */
public record AttributePredicateDef(
        String name,
        String description,
        Set<String> entityTypes,
        ValueType valueType,
        List<String> enumValues,
        Integer maxLength,
        BigDecimal min,
        BigDecimal max,
        Cardinality cardinality,
        Set<String> engineOnlyValues,
        boolean core) {

    public AttributePredicateDef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(cardinality, "cardinality");
        entityTypes = java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(entityTypes));
        enumValues = List.copyOf(enumValues == null ? List.of() : enumValues);
        engineOnlyValues = Set.copyOf(engineOnlyValues == null ? Set.of() : engineOnlyValues);
    }

    /** Enum values the LLM is allowed to assert. */
    public List<String> llmEnumValues() {
        return enumValues.stream().filter(v -> !engineOnlyValues.contains(v)).toList();
    }

    public AttributePredicateDef withCardinality(Cardinality c) {
        return new AttributePredicateDef(name, description, entityTypes, valueType, enumValues, maxLength, min, max,
                c, engineOnlyValues, core);
    }

    public AttributePredicateDef restrictTo(Set<String> enabled) {
        Set<String> kept = new java.util.TreeSet<>(entityTypes);
        kept.retainAll(enabled);
        return new AttributePredicateDef(name, description, kept, valueType, enumValues, maxLength, min, max,
                cardinality, engineOnlyValues, core);
    }
}
