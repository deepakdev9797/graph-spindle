package io.graphspindle.vocabulary;

/**
 * One problem found in a {@link VocabularyConfig}.
 *
 * @param path JSON-path-like location, e.g. {@code customEntityTypes[0].name}
 * @param code stable code
 * @param message human readable explanation
 */
public record VocabularyIssue(String path, Code code, String message) {

    public enum Code {
        UNKNOWN_PROFILE,
        INVALID_NAME,
        RESERVED_NAME,
        DUPLICATE_NAME,
        UNKNOWN_TYPE,
        UNKNOWN_PREDICATE,
        CANNOT_DISABLE,
        INVALID_VALUE,
        LIMIT_EXCEEDED
    }
}
