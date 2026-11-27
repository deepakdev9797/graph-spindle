package io.graphspindle.security;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * DNS-rebinding protection (ADR-034, MCP Streamable HTTP security requirement): the {@code Host} and,
 * when a browser sends one, the {@code Origin} header must match the allowlists
 * {@code graphspindle.security.allowed-hosts} / {@code allowed-origins}. Defaults: localhost, 127.0.0.1
 * and [::1] on any port. A pattern ending in {@code :*} matches that host with any (or no) port;
 * {@code *} matches everything. Applies to REST and MCP, in addition to API keys.
 */
@Component
public class HostOriginPolicy {

    private final List<String> origins;
    private final List<String> hosts;

    @org.springframework.beans.factory.annotation.Autowired
    public HostOriginPolicy(SecurityProperties properties) {
        this(properties.allowedOrigins(), properties.allowedHosts());
    }

    HostOriginPolicy(List<String> origins, List<String> hosts) {
        this.origins = origins.stream().map(o -> o.strip().toLowerCase(Locale.ROOT)).toList();
        this.hosts = hosts.stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
    }

    /** Why the request must be refused, or empty when it is allowed. Header values are never echoed. */
    public Optional<String> violation(String origin, String host) {
        if (host != null && !host.isBlank() && !matches(host, hosts)) {
            return Optional.of("request Host is not allowed (graphspindle.security.allowed-hosts)");
        }
        if (origin != null && !origin.isBlank() && !matches(origin, origins)) {
            return Optional.of("request Origin is not allowed (graphspindle.security.allowed-origins)");
        }
        return Optional.empty();
    }

    static boolean matches(String value, List<String> patterns) {
        String v = value.strip().toLowerCase(Locale.ROOT);
        for (String p : patterns) {
            if (p.equals("*") || p.equals(v)) {
                return true;
            }
            if (p.endsWith(":*")) {
                String base = p.substring(0, p.length() - 2);
                if (v.equals(base)) {
                    return true;
                }
                if (v.startsWith(base + ":")) {
                    String port = v.substring(base.length() + 1);
                    if (!port.isEmpty() && port.length() <= 5 && port.chars().allMatch(Character::isDigit)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
