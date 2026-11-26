package io.graphspindle.api.rest;

import io.graphspindle.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps request bodies (ARCHITECTURE §8, QA M2 F5): a declared {@code Content-Length} above the cap is
 * refused before anything is read, and a streamed (chunked) body is cut off once it exceeds the cap.
 * Both answer {@code 413 LIMIT_EXCEEDED}. The cap is {@code graphspindle.api.max-request-bytes}
 * (default 1 MiB).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestSizeLimitFilter(@Value("${graphspindle.api.max-request-bytes:1048576}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** Thrown when a streamed body exceeds the cap; mapped to 413 by {@link ApiExceptionHandler}. */
    static final class BodyTooLargeException extends IOException {

        BodyTooLargeException(long max) {
            super("request body exceeds " + max + " bytes");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            reject(response);
            return;
        }
        chain.doFilter(new Limited(request, maxBytes), response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Connection", "close");
        String body = "{\"type\":\"" + ApiExceptionHandler.type(ErrorCode.LIMIT_EXCEEDED) + "\",\"title\":\"Content Too Large\","
                + "\"status\":413,\"detail\":\"request body exceeds " + maxBytes + " bytes\",\"code\":\"LIMIT_EXCEEDED\"}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Limited extends HttpServletRequestWrapper {

        private final long max;
        private ServletInputStream stream;

        Limited(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new Counting(super.getInputStream(), max);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String enc = getCharacterEncoding();
            Charset cs = enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
            return new BufferedReader(new InputStreamReader(getInputStream(), cs));
        }
    }

    private static final class Counting extends ServletInputStream {

        private final ServletInputStream in;
        private final long max;
        private long count;

        Counting(ServletInputStream in, long max) {
            this.in = in;
            this.max = max;
        }

        private void add(long n) throws IOException {
            if (n > 0) {
                count += n;
                if (count > max) {
                    throw new BodyTooLargeException(max);
                }
            }
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            add(b < 0 ? 0 : 1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            add(n);
            return n;
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
