package io.graphspindle.vocabulary;

import java.math.BigDecimal;
import java.util.List;

/**
 * A node type property as shown and accepted by the node-type API (ADR-025).
 *
 * @param name lower-camel name, projected as {@code attr_<name>}
 * @param type value type
 * @param maxLength for STRING and URL
 * @param values for ENUM
 * @param min for NUMBER
 * @param max for NUMBER
 * @param cardinality ONE (default) or MANY
 * @param required advisory: record whenever known (not enforced)
 * @param description meaning given to the LLM (required when creating through the API)
 * @param synonyms alternative names
 * @param core read-only: true for a property from the YAML definition (ignored on input)
 */
public record PropertyDefinition(String name, ValueType type, Integer maxLength, List<String> values, BigDecimal min,
        BigDecimal max, Cardinality cardinality, Boolean required, String description, List<String> synonyms,
        Boolean core) {

    public PropertyDefinition {
        values = values == null ? List.of() : List.copyOf(values);
        synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
    }

    public static PropertyDefinition of(AttributePredicateDef a) {
        return new PropertyDefinition(a.name(), a.valueType(), a.maxLength(), a.enumValues(), a.min(), a.max(),
                a.cardinality(), a.required(), a.description(), a.synonyms(), a.core());
    }
}
