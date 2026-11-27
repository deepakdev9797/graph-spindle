package io.graphspindle.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start an exposed, unauthenticated server (ARCHITECTURE §8, ADR-033): bound to a
 * non-loopback address ({@code server.address} unset means every interface), the admin key is required
 * and {@code graphspindle.security.enabled=false} is refused. Checked while the context is created,
 * before the web server accepts connections.
 */
@Component
public class StartupSafety {

    private static final Logger log = LoggerFactory.getLogger(StartupSafety.class);

    private final boolean loopback;

    public StartupSafety(SecurityProperties properties, @Value("${server.address:}") String serverAddress) {
        this.loopback = verify(properties, serverAddress);
        if (!properties.enabled()) {
            log.warn("graphspindle.security.enabled=false: authentication is OFF and every request acts as the admin."
                    + " Allowed only because the server is bound to loopback ({}); never expose it.", serverAddress);
        } else if (!properties.hasAdminKey()) {
            log.warn("No admin API key configured (GRAPHSPINDLE_ADMIN_API_KEY): only stored API keys can authenticate,"
                    + " and no brains or keys can be created. Allowed only on loopback.");
        }
    }

    /** Whether the server is bound to loopback only. */
    public boolean loopback() {
        return loopback;
    }

    /**
     * @return whether the address is loopback
     * @throws IllegalStateException when the configuration would expose an unauthenticated server
     */
    public static boolean verify(SecurityProperties properties, String serverAddress) {
        if (properties.hasAdminKey() && properties.adminApiKey().strip().length() < SecurityProperties.MIN_ADMIN_KEY_LENGTH) {
            throw new IllegalStateException("graphspindle.security.admin-api-key is too short: use at least "
                    + SecurityProperties.MIN_ADMIN_KEY_LENGTH + " random characters (e.g. openssl rand -base64 32)");
        }
        boolean loopback = isLoopback(serverAddress);
        if (!loopback && !properties.enabled()) {
            throw new IllegalStateException("graphspindle.security.enabled=false is only allowed when server.address"
                    + " is a loopback address; the server would be bound to '" + display(serverAddress) + "'");
        }
        if (!loopback && !properties.hasAdminKey()) {
            throw new IllegalStateException("refusing to start: server.address '" + display(serverAddress)
                    + "' is not loopback and no admin API key is configured; set GRAPHSPINDLE_ADMIN_API_KEY"
                    + " or bind to 127.0.0.1");
        }
        return loopback;
    }

    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String a = address.strip();
        if (a.equalsIgnoreCase("localhost")) {
            return true;
        }
        try {
            return InetAddress.getByName(a).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static String display(String address) {
        return address == null || address.isBlank() ? "<all interfaces>" : address;
    }
}
