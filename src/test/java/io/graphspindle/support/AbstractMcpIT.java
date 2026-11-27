package io.graphspindle.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Base for MCP ITs (ADR-032): the ingestion IT setup (brains {@code dev} and {@code other}, fake LLM)
 * plus an MCP Java SDK client over Streamable HTTP at {@code /mcp}, and REST/MCP parity assertions.
 */
public abstract class AbstractMcpIT extends AbstractIngestionIT {

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[^\"]*");

    protected McpSyncClient mcp;

    /** One tool call: {@code error} is MCP's isError, {@code body} the parsed JSON text content. */
    public record ToolResult(boolean error, JsonNode body) {

        public JsonNode result() {
            return body.get("result");
        }

        public String code() {
            return body.path("error").path("code").asString(null);
        }
    }

    @BeforeEach
    void openMcp() {
        mcp = client();
        mcp.initialize();
    }

    @AfterEach
    void closeMcp() {
        if (mcp != null) {
            mcp.close();
        }
    }

    protected McpSyncClient client() {
        var transport = HttpClientStreamableHttpTransport.builder(baseUrl()).endpoint("/mcp").build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(60)).build();
    }

    protected ToolResult call(String tool, Object... keyValues) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                args.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        McpSchema.CallToolResult r = mcp.callTool(new McpSchema.CallToolRequest(tool, args, null));
        assertThat(r.content()).as(tool).hasSize(1);
        String text = ((McpSchema.TextContent) r.content().get(0)).text();
        return new ToolResult(Boolean.TRUE.equals(r.isError()), JSON.readTree(text));
    }

    /** The MCP result equals the REST body (2xx), or both carry the same error code, status and detail. */
    protected void assertParity(ToolResult m, ApiClient.Response rest) {
        if (rest.status() >= 200 && rest.status() < 300) {
            assertThat(m.error()).as("MCP error %s, REST %d", m.body(), rest.status()).isFalse();
            assertThat(m.body().get("untrusted").asBoolean()).isTrue();
            assertThat(m.result()).as("MCP result vs REST body").isEqualTo(rest.body());
        } else {
            assertErrorParity(m, rest);
        }
    }

    protected void assertErrorParity(ToolResult m, ApiClient.Response rest) {
        assertThat(m.error()).as("MCP must be a tool error for REST %d %s: %s", rest.status(), rest.body(), m.body())
                .isTrue();
        JsonNode e = m.body().get("error");
        assertThat(e.get("code").asString()).isEqualTo(rest.code());
        assertThat(e.get("status").asInt()).isEqualTo(rest.status());
        if (rest.body().has("detail")) {
            assertThat(e.get("detail").asString()).isEqualTo(rest.body().get("detail").asString());
        }
    }

    /** Ids, timestamps, durations and brain ids replaced, for comparing writes done in mirror brains. */
    protected static JsonNode normalized(JsonNode n) {
        return normalize(n, null);
    }

    private static JsonNode normalize(JsonNode n, String key) {
        if (n == null) {
            return null;
        }
        if (n.isObject()) {
            ObjectNode o = JSON.createObjectNode();
            n.propertyNames().forEach(k -> o.set(k, normalize(n.get(k), k)));
            return o;
        }
        if (n.isArray()) {
            ArrayNode a = JSON.createArrayNode();
            n.forEach(x -> a.add(normalize(x, null)));
            return a;
        }
        if ("brainId".equals(key) || "millis".equals(key)) {
            return JSON.getNodeFactory().stringNode("<" + key + ">");
        }
        if (n.isString()) {
            String s = UUID.matcher(n.asString()).replaceAll("<id>");
            s = TIMESTAMP.matcher(s).replaceAll("<t>");
            return JSON.getNodeFactory().stringNode(s);
        }
        return n;
    }
}
