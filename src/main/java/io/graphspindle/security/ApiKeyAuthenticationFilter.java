package io.graphspindle.security;

import io.graphspindle.common.Actor;
import io.graphspindle.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code /api/**}, {@code /mcp} and (when protected) the API docs with an API key in
 * {@code X-API-Key} or {@code Authorization: Bearer} (ADR-033). A valid key puts its {@link Actor} into
 * the security context and the request attribute {@link CurrentActor#REQUEST_ATTRIBUTE}; an invalid,
 * revoked or expired key is answered 401 here (one message for all, so nothing is revealed); no key at
 * all is left to the authorization rules (401 on protected paths). Keys are never logged.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";
    static final String INVALID = "invalid, revoked or expired API key";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final ApiKeyService keys;
    private final RateLimiter limiter;
    private final ProblemWriter problems;
    private final boolean enabled;

    public ApiKeyAuthenticationFilter(ApiKeyService keys, RateLimiter limiter, ProblemWriter problems,
            boolean enabled) {
        this.keys = keys;
        this.limiter = limiter;
        this.problems = problems;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            authenticate(request, Actor.LOCAL_ADMIN);
            chain.doFilter(request, response);
            return;
        }
        Optional<String> token;
        try {
            token = presentedKey(request);
        } catch (IllegalArgumentException e) {
            problems.write(response, ErrorCode.UNAUTHENTICATED, e.getMessage(), null);
            return;
        }
        if (token.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        String client = request.getRemoteAddr();
        long wait = limiter.failedAuthWait(client);
        if (wait > 0) {
            problems.write(response, ErrorCode.RATE_LIMITED, "too many failed authentication attempts", wait);
            return;
        }
        Optional<Actor> actor = keys.authenticate(token.get());
        if (actor.isEmpty()) {
            limiter.recordFailedAuth(client);
            log.warn("rejected API key from {} for {} {}", client, request.getMethod(), safePath(request));
            problems.write(response, ErrorCode.UNAUTHENTICATED, INVALID, null);
            return;
        }
        long requestWait = limiter.acquireRequest(actor.get());
        if (requestWait > 0) {
            problems.write(response, ErrorCode.RATE_LIMITED, "request rate limit exceeded", requestWait);
            return;
        }
        authenticate(request, actor.get());
        chain.doFilter(request, response);
    }

    private static void authenticate(HttpServletRequest request, Actor actor) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                List.of(new SimpleGrantedAuthority(actor.admin() ? "ROLE_ADMIN" : "ROLE_API_KEY")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        request.setAttribute(CurrentActor.REQUEST_ATTRIBUTE, actor);
    }

    /**
     * The key from {@code X-API-Key} or {@code Authorization: Bearer}; both may be sent only with the
     * same value.
     *
     * @throws IllegalArgumentException for a malformed or ambiguous header
     */
    static Optional<String> presentedKey(HttpServletRequest request) {
        String header = request.getHeader(API_KEY_HEADER);
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String bearer = null;
        if (authorization != null) {
            if (authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                bearer = authorization.substring(7).strip();
            } else {
                throw new IllegalArgumentException("unsupported Authorization scheme; use Bearer <API key>");
            }
        }
        String key = header == null ? null : header.strip();
        if (key != null && bearer != null && !key.equals(bearer)) {
            throw new IllegalArgumentException("X-API-Key and Authorization carry different keys");
        }
        String value = key != null ? key : bearer;
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** The request path, truncated, without query string (which could carry anything). */
    private static String safePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.length() > 120 ? uri.substring(0, 120) + "..." : uri;
    }
}
