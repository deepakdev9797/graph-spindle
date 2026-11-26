package io.graphspindle.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.llm.FakeChatModel;
import io.graphspindle.support.AbstractIngestionIT;
import io.graphspindle.support.ApiClient.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import tools.jackson.databind.JsonNode;

/** M6 acceptance: README example, follow-up without duplicates, explicit correction (SCHEMA §10.1–10.3). */
class ReadmeExampleIngestionIT extends AbstractIngestionIT {

    @Test
    void readmeExampleEndToEndThroughRest() {
        Response r = ingest("dev", "readme-example", "readme-1");
        assertThat(r.status()).isEqualTo(201);
        JsonNode b = r.body();
        assertThat(b.get("status").asString()).isEqualTo("APPLIED");
        assertThat(b.get("opsProposed").asInt()).isEqualTo(19);
        assertThat(b.get("opsApplied").asInt()).isEqualTo(19);
        assertThat(b.get("rejected")).isEmpty();
        assertThat(b.get("llm").get("calls").asInt()).isEqualTo(2);
        assertThat(b.get("llm").get("provider").asString()).isEqualTo("fake");
        assertThat(b.get("llm").get("promptVersion").asString()).isEqualTo("reconcile-v1");

        // the graph of SCHEMA §10.1: 6 entities plus self, 13 ACTIVE claims, the projection edges
        assertThat(nodes("dev", "Entity")).isEqualTo(7);
        assertThat(claims("dev", "ACTIVE")).isEqualTo(13);
        assertThat(nodes("dev", "Claim")).isEqualTo(13);
        List<Map<String, Object>> edges = query("""
                MATCH (a:Entity {brainId: 'dev'})-[r]->(b:Entity {brainId: 'dev'}) WHERE r.claimId IS NOT NULL
                RETURN a.name AS a, type(r) AS t, b.name AS b ORDER BY t, a, b""", Map.of());
        assertThat(edges).extracting(e -> e.get("t") + ":" + e.get("b")).containsExactlyInAnyOrder(
                "WORKS_ON:GraphSpindle", "WRITTEN_IN:Java", "USES:Neo4j", "USES:Spring AI",
                "LICENSED_UNDER:Apache License 2.0", "DECIDED:Use Apache License 2.0 for GraphSpindle",
                "APPLIES_TO:GraphSpindle", "CHOOSES:Apache License 2.0");
        assertThat(query("MATCH (e:Entity {brainId: 'dev', name: 'Java'}) RETURN e.attr_category AS c", Map.of()))
                .extracting(m -> m.get("c")).containsExactly("LANGUAGE");
        assertThat(count("MATCH (:Entity {brainId:'dev', canonicalKey:'standard:apache license 2.0'})-[:HAS_ALIAS]->"
                + "(a:Alias) RETURN count(a)", Map.of())).isGreaterThanOrEqualTo(3);

        // the Ingestion: APPLIED, no raw content, every claim evidenced by it, every entity mentioned in it
        Map<String, Object> ing = query("MATCH (i:Ingestion {brainId: 'dev'}) RETURN i.status AS s, i.rawContent AS raw,"
                + " i.opsApplied AS applied, i.llmProvider AS p, i.promptVersion AS v", Map.of()).get(0);
        assertThat(ing).containsEntry("s", "APPLIED").containsEntry("applied", 19L).containsEntry("v", "reconcile-v1");
        assertThat(ing.get("raw")).isNull();
        assertThat(count("MATCH (c:Claim {brainId:'dev'})-[:EVIDENCED_BY]->(:Ingestion) RETURN count(c)", Map.of()))
                .isEqualTo(13);

        // GET by id and by requestId return the stored result
        String id = b.get("ingestionId").asString();
        JsonNode byId = api().get("/api/v1/brains/dev/ingestions/" + id).body();
        assertThat(byId.get("status").asString()).isEqualTo("APPLIED");
        assertThat(byId.get("accepted").size()).isEqualTo(b.get("accepted").size());
        assertThat(api().get("/api/v1/brains/dev/ingestions?requestId=readme-1").body().get("ingestionId").asString())
                .isEqualTo(id);
        assertThat(api().get("/api/v1/brains/other/ingestions/" + id).status()).isEqualTo(404);

        // retrieval sees it
        assertThat(api().get("/api/v1/brains/dev/search?q=graph%20spindle").body().get("hits").toString())
                .contains("GraphSpindle");

        // prompts: delimited data, no tools, temperature 0, never the other brain
        for (FakeChatModel.Call c : fake.calls()) {
            assertThat(c.user()).containsPattern("<<<DATA nonce=[0-9a-f]{16} name=content>>>");
            assertThat(c.options().getTemperature()).isEqualTo(0.0);
            assertThat(c.options() instanceof ToolCallingChatOptions t && !t.getToolCallbacks().isEmpty()).isFalse();
            assertThat(c.text()).doesNotContain(io.graphspindle.common.Ids.class.getSimpleName());
        }
        assertOtherBrainUntouched();
    }

    @Test
    void followUpCreatesNoDuplicatesReinforcesAndLinksTheSourceWithoutReadingIt() {
        assertThat(ingest("dev", "readme-example", null).status()).isEqualTo(201);
        endToEnd("followup-update");
        Response r = ingest("dev", "followup-update", null);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("rejected")).isEmpty();
        assertThat(r.body().get("opsApplied").asInt()).isEqualTo(9);

        assertThat(count("MATCH (e:Entity {brainId:'dev', canonicalKey:'project:graphspindle'}) RETURN count(e)",
                Map.of())).isEqualTo(1);
        assertThat(count("MATCH (e:Entity {brainId:'dev', canonicalKey:'technology:neo4j'}) RETURN count(e)",
                Map.of())).isEqualTo(1);
        assertThat(query("MATCH (c:Claim {brainId:'dev', predicate:'USES', status:'ACTIVE'})-[:OBJECT]->"
                + "(:Entity {name:'Neo4j'}) RETURN c.evidenceCount AS n", Map.of())).extracting(m -> m.get("n"))
                .containsExactly(2L);
        assertThat(r.body().get("accepted").toString()).contains("CLAIM_REINFORCED").contains("SOURCE_LINKED");
        assertThat(query("MATCH (s:SourceRef {brainId:'dev'})-[:DESCRIBES]->(e:Entity) RETURN s.kind AS k, s.locator AS l,"
                + " e.name AS n", Map.of())).singleElement().satisfies(m -> assertThat(m)
                        .containsEntry("k", "FILE").containsEntry("l", "docs/ARCHITECTURE.md")
                        .containsEntry("n", "GraphSpindle"));
        assertThat(query("MATCH (e:Entity {brainId:'dev', name:'GraphSpindle'}) RETURN e.mentionCount AS m", Map.of())
                .get(0).get("m")).isEqualTo(2L);
        // the candidates were shown by handle, never by id
        FakeChatModel.Call reconcile = fake.calls("reconcile-v1").get(1);
        String gsId = query("MATCH (e:Entity {brainId:'dev', name:'GraphSpindle'}) RETURN e.id AS id", Map.of()).get(0)
                .get("id").toString();
        assertThat(reconcile.user()).contains("\"name\":\"GraphSpindle\"").doesNotContain(gsId);
        assertOtherBrainUntouched();
    }

    @Test
    void explicitCorrectionSupersedesAndTheOldDecisionIsSuperseded() {
        assertThat(ingest("dev", "readme-example", null).status()).isEqualTo(201);
        endToEnd("explicit-correction");
        Response r = ingest("dev", "explicit-correction", null);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("rejected")).isEmpty();
        assertThat(r.body().get("adjusted").toString()).doesNotContain("CORRECTION_DOWNGRADED");

        List<Map<String, Object>> licenses = query("""
                MATCH (c:Claim {brainId:'dev', predicate:'LICENSED_UNDER'})-[:OBJECT]->(o:Entity)
                RETURN o.name AS o, c.status AS s ORDER BY c.recordedAt""", Map.of());
        assertThat(licenses).extracting(m -> m.get("o") + "=" + m.get("s"))
                .containsExactly("Apache License 2.0=SUPERSEDED", "MIT License=ACTIVE");
        assertThat(count("MATCH (:Claim {brainId:'dev', status:'ACTIVE', predicate:'LICENSED_UNDER'})-[:SUPERSEDES]->"
                + "(:Claim {status:'SUPERSEDED'}) RETURN count(*)", Map.of())).isEqualTo(1);
        assertThat(query("MATCH (d:Entity:Decision {brainId:'dev', name:'Use Apache License 2.0 for GraphSpindle'})"
                + " RETURN d.attr_status AS s", Map.of())).extracting(m -> m.get("s")).containsExactly("SUPERSEDED");
        assertThat(count("MATCH (:Entity {brainId:'dev', name:'Use MIT License for GraphSpindle'})-[:REPLACES]->"
                + "(:Entity {name:'Use Apache License 2.0 for GraphSpindle'}) RETURN count(*)", Map.of())).isEqualTo(1);
        // the projection follows: GraphSpindle -> MIT only
        assertThat(query("MATCH (:Entity {brainId:'dev', name:'GraphSpindle'})-[:LICENSED_UNDER]->(o) RETURN o.name AS o",
                Map.of())).extracting(m -> m.get("o")).containsExactly("MIT License");
        assertOtherBrainUntouched();
    }
}
