package io.graphspindle.ingestion.ops;

/**
 * The model output could not be read as a proposal at all (not JSON, truncated, no operations
 * array, too large). Triggers the one repair retry, then {@code MALFORMED_MODEL_OUTPUT}.
 */
public class MalformedModelOutputException extends RuntimeException {

    public MalformedModelOutputException(String message) {
        super(message);
    }
}
