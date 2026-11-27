package io.graphspindle.api.mcp;

import io.graphspindle.common.Actor;
import io.graphspindle.security.CurrentActor;
import io.modelcontextprotocol.common.McpTransportContext;
import io.graphspindle.security.HostOriginPolicy;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.mcp.customizer.McpServerCustomizer;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP server wiring (ADR-032). Tools are the {@code @McpTool} methods of {@link McpTools}, picked up
 * by the Spring AI annotation scanner; the transport is Streamable HTTP at {@code /mcp} on the same
 * server (and bind address) as REST.
 */
@Configuration(proxyBeanMethods = false)
public class McpConfig {

    /**
     * The {@code Actor} that the API key filter (ADR-033) stores in the request attribute
     * {@value CurrentActor#REQUEST_ATTRIBUTE} reaches the tools through the transport context
     * (independent of the thread a tool runs on).
     */
    @Bean
    McpServerCustomizer<WebMvcStreamableServerTransportProvider.Builder> mcpPrincipalExtractor(
            HostOriginPolicy hostOrigin,
            @Value("${graphspindle.mcp.session-idle-timeout:30m}") Duration sessionIdleTimeout,
            @Value("${graphspindle.mcp.max-sessions:1000}") long maxSessions) {
        return builder -> builder
                // DNS-rebinding protection required by the MCP spec, also enforced by HostOriginFilter (ADR-034)
                .securityValidator(headers -> {
                    var violation = hostOrigin.violation(header(headers, "Origin"), header(headers, "Host"));
                    if (violation.isPresent()) {
                        throw new ServerTransportSecurityException(403, violation.get());
                    }
                })
                // abandoned sessions expire; the cap bounds memory (ADR-034)
                .sessionIdleTimeout(sessionIdleTimeout)
                .maxSessions(maxSessions)
                .contextExtractor(request -> request.attribute(CurrentActor.REQUEST_ATTRIBUTE)
                .filter(Actor.class::isInstance)
                .map(p -> McpTransportContext.create(Map.of(CurrentActor.REQUEST_ATTRIBUTE, p)))
                .orElse(McpTransportContext.EMPTY));
    }

    private static String header(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (name.equalsIgnoreCase(e.getKey()) && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }
}
