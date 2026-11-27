package io.graphspindle.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code graphspindle.security.*} (ADR-033). {@link #toString()} never prints the admin key.
 *
 * @param enabled false disables authentication (every request acts as the admin); allowed only when
 *     the server is bound to a loopback address, otherwise startup fails
 * @param adminApiKey the bootstrap admin key ({@code GRAPHSPINDLE_ADMIN_API_KEY}), at least 32
 *     characters; required when the server is bound to a non-loopback address. Only its hash is kept.
 * @param apiDocs who may read Swagger UI and {@code /v3/api-docs}
 * @param rateLimit in-memory, single-instance limits
 * @param allowedOrigins browser {@code Origin} values accepted on REST and MCP (ADR-034)
 * @param allowedHosts {@code Host} header values accepted on REST and MCP (ADR-034)
 */
@ConfigurationProperties("graphspindle.security")
public record SecurityProperties(@DefaultValue("true") boolean enabled, String adminApiKey,
        @DefaultValue("AUTO") ApiDocsAccess apiDocs, @DefaultValue RateLimit rateLimit,
        @DefaultValue({"http://localhost:*", "http://127.0.0.1:*", "http://[::1]:*", "https://localhost:*",
            "https://127.0.0.1:*", "https://[::1]:*"}) java.util.List<String> allowedOrigins,
        @DefaultValue({"localhost:*", "127.0.0.1:*", "[::1]:*"}) java.util.List<String> allowedHosts) {

    public static final int MIN_ADMIN_KEY_LENGTH = 32;

    /** {@code AUTO}: public while bound to loopback, authenticated otherwise. */
    public enum ApiDocsAccess {
        AUTO,
        PUBLIC,
        AUTHENTICATED
    }

    /**
     * @param requestsPerMinute authenticated {@code /api} and {@code /mcp} requests per principal
     * @param ingestPerMinute ingestions (REST and MCP {@code remember}) per principal
     * @param failedAuthPerMinute requests with an invalid key per client address before 429
     */
    public record RateLimit(@DefaultValue("300") int requestsPerMinute, @DefaultValue("30") int ingestPerMinute,
            @DefaultValue("20") int failedAuthPerMinute) {
    }

    public boolean hasAdminKey() {
        return adminApiKey != null && !adminApiKey.isBlank();
    }

    @Override
    public String toString() {
        return "SecurityProperties[enabled=" + enabled + ", adminApiKey=" + (hasAdminKey() ? "<redacted>" : "<none>")
                + ", apiDocs=" + apiDocs + ", rateLimit=" + rateLimit + ", allowedOrigins=" + allowedOrigins
                + ", allowedHosts=" + allowedHosts + "]";
    }
}
