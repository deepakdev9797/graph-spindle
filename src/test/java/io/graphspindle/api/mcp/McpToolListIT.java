package io.graphspindle.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractMcpIT;
import io.graphspindle.support.KnowledgeApi;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M8: the MCP client lists exactly the expected tools, with argument schemas (ADR-032). */
class McpToolListIT extends AbstractMcpIT {

    static final Set<String> TOOLS = Set.of("list_brains", "get_brain", "list_node_types", "list_relationship_types",
            "remember", "get_ingestion", "search_knowledge", "list_entities", "get_entity", "get_entity_history",
            "get_neighbors", "list_claims", "get_claim", "get_claim_history", "assert_claim", "supersede_claim",
            "retract_claim", "list_conflicts", "get_conflict", "resolve_conflict");

    @Test
    void exactlyTheExpectedToolsWithSchemas() {
        List<McpSchema.Tool> tools = mcp.listTools().tools();
        assertThat(tools).extracting(McpSchema.Tool::name).containsExactlyInAnyOrderElementsOf(TOOLS);
        for (McpSchema.Tool t : tools) {
            assertThat(t.description()).as(t.name()).isNotBlank();
            JsonNode schema = JSON.valueToTree(t.inputSchema());
            assertThat(schema.get("type").asString()).as(t.name()).isEqualTo("object");
            if (!t.name().equals("list_brains")) {
                // the brain is always an explicit, required argument
                assertThat(schema.get("properties").has("brainId")).as(t.name()).isTrue();
                assertThat(KnowledgeApi.texts(schema.get("required"), null)).as(t.name()).contains("brainId");
            }
            // the transport context is injected, never an argument
            assertThat(schema.path("properties").has("ctx")).as(t.name()).isFalse();
        }
        McpSchema.Tool remember = tools.stream().filter(t -> t.name().equals("remember")).findFirst().orElseThrow();
        JsonNode schema = JSON.valueToTree(remember.inputSchema());
        System.out.println("remember schema: " + schema);
        assertThat(KnowledgeApi.texts(schema.get("required"), null)).containsExactlyInAnyOrder("brainId", "content");
        assertThat(schema.get("properties").propertyNames()).contains("contentType", "requestId", "sources",
                "speaker", "occurredAt", "dryRun");
        assertThat(schema.get("properties").get("sources").toString()).contains("locator", "mediaType");
        assertThat(remember.description()).contains("never need Cypher");
        assertThat(tools.stream().filter(t -> t.name().equals("get_entity")).findFirst().orElseThrow()
                .annotations().readOnlyHint()).isTrue();
    }
}
