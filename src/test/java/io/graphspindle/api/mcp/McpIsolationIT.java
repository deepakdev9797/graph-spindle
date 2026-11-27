package io.graphspindle.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractMcpIT;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M8 McpIsolation: ids of brain {@code other} under a {@code dev} argument are NOT_FOUND, like REST. */
class McpIsolationIT extends AbstractMcpIT {

    static final String B = "/api/v1/brains/";

    @Test
    void otherBrainIdsAreNotFoundAndNothingLeaks() {
        String foreign = k.entity("other", "Technology", "Kafka");
        String foreignProject = api().get(B + "other/search?q=GraphSpindle").body().get("hits").get(0).get("entity")
                .get("id").asString();
        String foreignClaim = k.relation("other", foreignProject, "USES", foreign);
        String devProject = k.entity("dev", "Project", "GraphSpindle");

        for (ToolResult r : new ToolResult[] {
                call("get_entity", "brainId", "dev", "entityId", foreign),
                call("get_entity", "brainId", "dev", "entityId", foreign, "includeClaims", true),
                call("get_entity_history", "brainId", "dev", "entityId", foreign),
                call("get_neighbors", "brainId", "dev", "entityId", foreign),
                call("get_claim", "brainId", "dev", "claimId", foreignClaim),
                call("get_claim_history", "brainId", "dev", "claimId", foreignClaim),
                call("retract_claim", "brainId", "dev", "claimId", foreignClaim, "reason", "x"),
                call("supersede_claim", "brainId", "dev", "claimId", foreignClaim, "reason", "x", "objectId",
                        devProject),
                call("assert_claim", "brainId", "dev", "subjectId", devProject, "predicate", "USES", "objectId",
                        foreign)}) {
            assertThat(r.error()).as(r.body().toString()).isTrue();
            assertThat(r.code()).as(r.body().toString()).isEqualTo("NOT_FOUND");
        }
        assertErrorParity(call("assert_claim", "brainId", "dev", "subjectId", devProject, "predicate", "USES",
                "objectId", foreign), k.assertRelation("dev", devProject, "USES", foreign));
        assertErrorParity(call("get_entity", "brainId", "dev", "entityId", foreign),
                api().get(B + "dev/entities/" + foreign));

        // listings and search in dev never show other's data
        JsonNode hits = call("search_knowledge", "brainId", "dev", "query", "Kafka").result().get("hits");
        assertThat(hits).isEmpty();
        assertThat(call("search_knowledge", "brainId", "dev", "query", SENTINEL).result().get("hits")).isEmpty();
        assertThat(call("list_claims", "brainId", "dev", "subjectId", foreignProject).result().get("items")).isEmpty();
        assertThat(call("list_entities", "brainId", "dev").result().toString()).doesNotContain("Kafka", SENTINEL);
        // and the foreign claim is untouched
        assertThat(api().get(B + "other/claims/" + foreignClaim).body().get("claim").get("status").asString())
                .isEqualTo("ACTIVE");
    }
}
