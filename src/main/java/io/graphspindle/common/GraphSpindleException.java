package io.graphspindle.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Application error with a stable {@link ErrorCode}. Messages must not contain untrusted content
 * beyond short, truncated identifiers. {@code details} are extra machine-readable properties.
 */
public class GraphSpindleException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public GraphSpindleException(ErrorCode code, String message) {
        this(code, message, Map.of(), null);
    }

    public GraphSpindleException(ErrorCode code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.details = details == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static GraphSpindleException brainNotFound(String brainId) {
        return new GraphSpindleException(ErrorCode.BRAIN_NOT_FOUND,
                "brain not found: " + Normalizer.truncate(String.valueOf(brainId), 63));
    }

    public static GraphSpindleException notFound(String what, String id) {
        return new GraphSpindleException(ErrorCode.NOT_FOUND,
                what + " not found: " + Normalizer.truncate(String.valueOf(id), 64));
    }

    public static GraphSpindleException validation(String message) {
        return new GraphSpindleException(ErrorCode.VALIDATION_FAILED, message);
    }
}
