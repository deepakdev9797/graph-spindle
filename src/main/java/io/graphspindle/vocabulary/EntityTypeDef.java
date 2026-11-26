package io.graphspindle.vocabulary;

import java.util.List;
import java.util.Objects;

/**
 * An entity type (Neo4j label) of the controlled vocabulary.
 *
 * @param name the label, e.g. {@code Project}
 * @param description one line given to the LLM
 * @param examples short examples given to the LLM
 * @param compatibilityGroup the type compatibility group for entity resolution (SCHEMA §6.2), or null
 * @param core true when it comes from the core profile
 */
public record EntityTypeDef(String name, String description, List<String> examples, String compatibilityGroup,
        boolean core) {

    public EntityTypeDef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        examples = List.copyOf(examples == null ? List.of() : examples);
    }
}
