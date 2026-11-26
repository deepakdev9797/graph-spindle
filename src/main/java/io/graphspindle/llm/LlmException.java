package io.graphspindle.llm;

/** The model could not be used for this call. Messages never contain prompt or output text. */
public class LlmException extends RuntimeException {

    public enum Kind {
        /** No model configured, or disabled. */
        UNAVAILABLE,
        /** The call did not finish within its deadline. */
        TIMEOUT,
        /** The provider answered with an error or could not be reached. */
        FAILED
    }

    private final Kind kind;

    public LlmException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
