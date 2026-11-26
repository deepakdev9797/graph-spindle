package io.graphspindle.vocabulary;

import java.util.List;

/**
 * A node (entity) type as shown and accepted by the node-type API (ADR-025). On PUT, fields left
 * null keep their current value; lists that are given replace the current list.
 *
 * @param name upper-camel label
 * @param description one line for the LLM (required on create)
 * @param synonyms alternative names, rendered into the prompt
 * @param examples short examples, rendered into the prompt
 * @param compatibilityGroup type compatibility group for entity resolution, or null
 * @param core read-only: true for a type from the YAML definition
 * @param enabled whether the type is enabled in the brain (only core types can be disabled)
 * @param properties the type's properties; for a core type, entries not in the YAML are the brain's
 *        extra properties
 */
public record NodeType(String name, String description, List<String> synonyms, List<String> examples,
        String compatibilityGroup, Boolean core, Boolean enabled,
        List<PropertyDefinition> properties) {
}
