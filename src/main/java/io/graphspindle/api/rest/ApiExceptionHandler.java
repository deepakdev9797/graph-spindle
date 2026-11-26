package io.graphspindle.api.rest;

import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Maps errors to RFC 9457 problem details carrying the shared {@code code} (ARCHITECTURE §13). */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static URI type(ErrorCode code) {
        return URI.create("urn:graphspindle:problem:" + code.name().toLowerCase(java.util.Locale.ROOT));
    }

    static ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(code.httpStatus()), detail);
        pd.setType(type(code));
        pd.setProperty("code", code.name());
        return pd;
    }

    @ExceptionHandler(GraphSpindleException.class)
    ResponseEntity<ProblemDetail> handle(GraphSpindleException e) {
        if (e.code() == ErrorCode.INTERNAL) {
            log.error("internal error", e);
        }
        ProblemDetail pd = problem(e.code(), e.getMessage());
        e.details().forEach(pd::setProperty);
        return ResponseEntity.status(e.code().httpStatus()).body(pd);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("unexpected error", e);
        return ResponseEntity.status(500).body(problem(ErrorCode.INTERNAL, "internal error"));
    }

    /** Framework errors (unreadable body, bad parameter, 404 route...) also get a {@code code}. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail pd) {
            ErrorCode code = switch (statusCode.value()) {
                case 400, 415, 422 -> ErrorCode.VALIDATION_FAILED;
                case 404 -> ErrorCode.NOT_FOUND;
                case 405, 406 -> ErrorCode.VALIDATION_FAILED;
                case 413 -> ErrorCode.LIMIT_EXCEEDED;
                default -> statusCode.is5xxServerError() ? ErrorCode.INTERNAL : ErrorCode.VALIDATION_FAILED;
            };
            pd.setProperty("code", code.name());
            if (pd.getType() == null || "about:blank".equals(pd.getType().toString())) {
                pd.setType(type(code));
            }
        }
        return response;
    }
}
