package io.graphspindle.vocabulary;

import java.util.List;
import java.util.Objects;

/**
 * An entity type (Neo4j label) of the controlled vocabulary.
 *
 * @param name the label, e.g. {@code Project}
 * @param description one line given to the LLM
 * @param synonyms alternative names a human or the LLM might use for this type (prompt and
 *        similarity guardrail, ADR-025)
 * @param examples short examples given to the LLM
 * @param compatibilityGroup the type compatibility group for entity resolution (SCHEMA §6.2), or null
 * @param core true when it comes from the core profile
 */
public record EntityTypeDef(String name, String description, List<String> synonyms, List<String> examples,
        String compatibilityGroup, boolean core) {

    public EntityTypeDef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        synonyms = List.copyOf(synonyms == null ? List.of() : synonyms);
        examples = List.copyOf(examples == null ? List.of() : examples);
    }

    /** A type without synonyms. */
    public EntityTypeDef(String name, String description, List<String> examples, String compatibilityGroup,
            boolean core) {
        this(name, description, List.of(), examples, compatibilityGroup, core);
    }
}
