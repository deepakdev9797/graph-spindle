package io.graphspindle.security;

import io.graphspindle.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses (403) requests whose {@code Host} or {@code Origin} is not allowlisted ({@link HostOriginPolicy}),
 * before authentication, on every path except {@code /actuator/health} (container probes).
 */
public class HostOriginFilter extends OncePerRequestFilter {

    private final HostOriginPolicy policy;
    private final ProblemWriter problems;

    public HostOriginFilter(HostOriginPolicy policy, ProblemWriter problems) {
        this.policy = policy;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> violation = policy.violation(request.getHeader(HttpHeaders.ORIGIN),
                request.getHeader(HttpHeaders.HOST));
        if (violation.isPresent()) {
            problems.write(response, ErrorCode.FORBIDDEN, violation.get(), null);
            return;
        }
        chain.doFilter(request, response);
    }
}
