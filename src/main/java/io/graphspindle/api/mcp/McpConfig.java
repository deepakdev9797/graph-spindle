package io.graphspindle.api.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import java.util.Map;
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
     * The authentication extension point for M9: whatever principal the HTTP security filter stores
     * in the request attribute {@value McpCaller#PRINCIPAL_ATTRIBUTE} reaches the tools through the
     * transport context (independent of the thread a tool runs on).
     */
    @Bean
    McpServerCustomizer<WebMvcStreamableServerTransportProvider.Builder> mcpPrincipalExtractor() {
        return builder -> builder.contextExtractor(request -> request.attribute(McpCaller.PRINCIPAL_ATTRIBUTE)
                .filter(String.class::isInstance)
                .map(p -> McpTransportContext.create(Map.of(McpCaller.PRINCIPAL_ATTRIBUTE, p)))
                .orElse(McpTransportContext.EMPTY));
    }
}
