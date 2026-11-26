package io.graphspindle.ingestion.validation;

import java.util.Objects;

/**
 * One rejected operation.
 *
 * @param opIndex index in the model's {@code operations} array, or -1 for the envelope
 * @param op the wire op name, or null when unknown
 * @param code stable rejection code
 * @param message explanation; untrusted text in it is truncated
 */
public record ValidationIssue(int opIndex, String op, RejectionCode code, String message) {

    public ValidationIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
