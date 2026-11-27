package io.graphspindle.security;

import io.graphspindle.common.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** RFC 9457 problem responses written by the security layer, in the same shape as the REST errors. */
@Component
public class ProblemWriter {

    private final JsonMapper json;

    public ProblemWriter(JsonMapper json) {
        this.json = json;
    }

    public void write(HttpServletResponse response, ErrorCode code, String detail, Long retryAfterSeconds)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:graphspindle:problem:" + code.name().toLowerCase(Locale.ROOT));
        body.put("title", HttpStatus.valueOf(code.httpStatus()).getReasonPhrase());
        body.put("status", code.httpStatus());
        body.put("detail", detail);
        body.put("code", code.name());
        if (code == ErrorCode.UNAUTHENTICATED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"graphspindle\"");
        }
        if (retryAfterSeconds != null) {
            body.put("retryAfterSeconds", retryAfterSeconds);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        }
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(json.writeValueAsBytes(body));
    }
}
