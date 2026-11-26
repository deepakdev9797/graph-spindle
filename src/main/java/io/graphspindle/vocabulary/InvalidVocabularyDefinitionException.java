package io.graphspindle.vocabulary;

import java.util.List;

/**
 * The YAML vocabulary definition is unreadable or invalid. Thrown at startup so the application
 * fails fast with every problem listed (ADR-025).
 */
public class InvalidVocabularyDefinitionException extends IllegalStateException {

    private final transient List<String> problems;

    public InvalidVocabularyDefinitionException(String source, List<String> problems, Throwable cause) {
        super("invalid vocabulary definition " + source + ": " + problems.size() + " problem(s):\n  - "
                + String.join("\n  - ", problems), cause);
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
