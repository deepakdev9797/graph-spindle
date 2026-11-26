package io.graphspindle.vocabulary;

import java.util.List;

/** Thrown when a vocabulary configuration fails validation. Maps to {@code VALIDATION_FAILED}. */
public class InvalidVocabularyConfigException extends RuntimeException {

    private final transient List<VocabularyIssue> issues;

    public InvalidVocabularyConfigException(List<VocabularyIssue> issues) {
        super("invalid vocabulary configuration: " + issues.size() + " issue(s); first: "
                + (issues.isEmpty() ? "-" : issues.get(0).path() + " " + issues.get(0).code()));
        this.issues = List.copyOf(issues);
    }

    public List<VocabularyIssue> issues() {
        return issues;
    }
}
