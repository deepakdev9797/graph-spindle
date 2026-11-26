package io.graphspindle.common;

/** Error codes shared by REST and MCP (ARCHITECTURE §13), with their HTTP status. */
public enum ErrorCode {
    UNAUTHENTICATED(401),
    FORBIDDEN(403),
    BRAIN_NOT_FOUND(404),
    NOT_FOUND(404),
    VALIDATION_FAILED(400),
    LIMIT_EXCEEDED(400),
    RATE_LIMITED(429),
    /** A resource with the same client-chosen id already exists (brain id). ADR-019. */
    ALREADY_EXISTS(409),
    /** {@code If-Match} did not match the current {@code configVersion}. ADR-019. */
    PRECONDITION_FAILED(412),
    IDEMPOTENCY_KEY_REUSED(409),
    IN_PROGRESS(409),
    CONFLICT_STATE(409),
    LLM_UNAVAILABLE(503),
    MALFORMED_MODEL_OUTPUT(502),
    INTERNAL(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
