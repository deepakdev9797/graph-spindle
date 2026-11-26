package io.graphspindle.vocabulary;

import java.util.Objects;

/**
 * A classification hint rendered into the prompt (SCHEMA §6.1).
 *
 * @param text the hint
 * @param requiresType the entity type that must be enabled for the hint to be shown, or null
 */
public record ClassificationHint(String text, String requiresType) {

    public ClassificationHint {
        Objects.requireNonNull(text, "text");
    }
}
