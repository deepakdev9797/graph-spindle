package io.graphspindle.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractMcpIT;
import io.graphspindle.support.ApiClient.Response;
import io.graphspindle.support.TestKeys;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Autowired;

/** QA M8: transport security and REST/MCP argument parity. */
class QaM8McpIT extends AbstractMcpIT {

    static final String INIT = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
             "capabilities":{},"clientInfo":{"name":"evil","version":"1"}}}""";

    @Autowired
    WebMvcStreamableServerTransportProvider transport;

    private HttpResponse<String> post(String body, String origin, String session) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                // authenticated (M9): the Origin check must hold even for a caller with a valid key
                .header("X-API-Key", TestKeys.ADMIN)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) {
            b.header("Origin", origin);
        }
        if (session != null) {
            b.header("Mcp-Session-Id", session);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * MCP Streamable HTTP spec (Security Warning): servers MUST validate the Origin header to prevent
     * DNS rebinding. A page on http://evil.example that rebinds its name to 127.0.0.1 can drive every
     * tool (no auth until M9) - including assert/supersede/retract/resolve_conflict.
     */
    @Test
    void crossOriginRequestsAreRejected() throws Exception {
        HttpResponse<String> init = post(INIT, "http://evil.example", null);
        assertThat(init.statusCode()).as("initialize from a foreign Origin: %s", init.body()).isEqualTo(403);
        // a local origin is fine, and REST applies the same Origin check
        assertThat(post(INIT, "http://localhost:3000", null).statusCode()).isEqualTo(200);
        var rest = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/brains"))
                .header("X-API-Key", TestKeys.ADMIN).header("Origin", "http://evil.example").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(rest.statusCode()).isEqualTo(403);
        assertThat(rest.body()).contains("FORBIDDEN").doesNotContain("evil.example");
    }

    @Test
    void crossOriginToolCallReachesTheGraph() throws Exception {
        HttpResponse<String> init = post(INIT, "http://evil.example", null);
        String session = init.headers().firstValue("Mcp-Session-Id").orElse(null);
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", "http://evil.example", session);
        HttpResponse<String> call = post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_brains","arguments":{}}}""",
                "http://evil.example", session);
        assertThat(call.body()).as("tool call from a foreign Origin returned the brains").doesNotContain("untrusted");
    }

    /** Sessions are never evicted (no idle timeout) and capped at 100k: unauthenticated init floods fill memory
     *  and then refuse every new MCP client with 503 until restart. */
    @Test
    void idleSessionsAreEvicted() throws Exception {
        Field idle = WebMvcStreamableServerTransportProvider.class.getDeclaredField("sessionIdleTimeout");
        idle.setAccessible(true);
        Field max = WebMvcStreamableServerTransportProvider.class.getDeclaredField("maxSessions");
        max.setAccessible(true);
        Field sessions = WebMvcStreamableServerTransportProvider.class.getDeclaredField("sessions");
        sessions.setAccessible(true);
        int before = ((Map<?, ?>) sessions.get(transport)).size();
        for (int i = 0; i < 20; i++) {
            post(INIT, null, null); // never DELETEd, like most clients that just exit
        }
        assertThat(((Map<?, ?>) sessions.get(transport)).size()).isGreaterThanOrEqualTo(before + 20);
        assertThat((long) max.get(transport)).as("maxSessions").isLessThanOrEqualTo(1000);
        assertThat(idle.get(transport)).as("sessionIdleTimeout").isNotNull();
    }

    /** REST rejects a misspelled source field (strict JSON); MCP silently drops it and runs the ingestion. */
    @Test
    void unknownSourceFieldIsRejectedLikeRest() {
        Map<String, Object> source = Map.of("kind", "URL", "locator", "https://example.org/a", "titel", "typo");
        Response rest = ingest("dev", Map.of("content", "GraphSpindle uses Neo4j.", "sources", List.of(source)), "");
        assertThat(rest.status()).isEqualTo(400);
        McpSchema.CallToolResult m = mcp.callTool(new McpSchema.CallToolRequest("remember", Map.of("brainId", "dev",
                "content", "GraphSpindle uses Neo4j.", "sources", List.of(source)), null));
        String text = ((McpSchema.TextContent) m.content().get(0)).text();
        assertThat(text).contains("\"code\":\"" + rest.code() + "\"");
    }
}
