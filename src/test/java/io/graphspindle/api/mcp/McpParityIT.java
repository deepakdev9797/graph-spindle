package io.graphspindle.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractMcpIT;
import io.graphspindle.support.ApiClient.Response;
import io.graphspindle.support.KnowledgeApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * M8 RestMcpParity: every tool and its REST endpoint on the same data give the same result, or the
 * same error code, status and detail (ADR-032). Writes run in two mirror brains (REST in one, MCP in
 * the other) and are compared with ids and timestamps normalized.
 */
class McpParityIT extends AbstractMcpIT {

    static final String B = "/api/v1/brains/";

    @Test
    void rememberAndGetIngestionMatchRest() {
        k.brain("dev2");
        Response rest = ingest("dev", "readme-example", "readme-1");
        assertThat(rest.status()).isEqualTo(201);
        fake.load(fixture("readme-example"));
        JsonNode d = fixture("readme-example");
        ToolResult m = call("remember", "brainId", "dev2", "content", d.get("content").asString(), "requestId",
                "readme-1", "sources", d.has("sources") ? JSON.convertValue(d.get("sources"), List.class) : null,
                "speaker", d.has("speaker") ? JSON.convertValue(d.get("speaker"), Map.class) : null);
        assertThat(m.error()).as(m.body().toString()).isFalse();
        assertThat(m.result().get("status").asString()).isEqualTo("APPLIED");
        assertThat(normalized(m.result())).isEqualTo(normalized(rest.body()));

        String id = m.result().get("ingestionId").asString();
        assertParity(call("get_ingestion", "brainId", "dev2", "ingestionId", id), api().get(B + "dev2/ingestions/" + id));
        assertParity(call("get_ingestion", "brainId", "dev2", "requestId", "readme-1"),
                api().get(B + "dev2/ingestions?requestId=readme-1"));
        // a replay is a replay over both
        ToolResult replay = call("remember", "brainId", "dev2", "content", d.get("content").asString(), "requestId",
                "readme-1", "sources", d.has("sources") ? JSON.convertValue(d.get("sources"), List.class) : null,
                "speaker", d.has("speaker") ? JSON.convertValue(d.get("speaker"), Map.class) : null);
        assertThat(replay.result().get("replayed").asBoolean()).isTrue();
        assertThat(fake.calls()).hasSize(4);
    }

    @Test
    void readToolsMatchRest() {
        assertThat(ingest("dev", "readme-example", null).status()).isEqualTo(201);
        String project = api().get(B + "dev/search?q=GraphSpindle").body().get("hits").get(0).get("entity").get("id")
                .asString();
        String claim = api().get(B + "dev/claims?subjectId=" + project).body().get("items").get(0).get("id")
                .asString();

        assertParity(call("list_brains"), api().get("/api/v1/brains"));
        assertParity(call("get_brain", "brainId", "dev"), api().get(B + "dev"));
        assertParity(call("list_node_types", "brainId", "dev"), api().get(B + "dev/node-types"));
        assertParity(call("list_relationship_types", "brainId", "dev"), api().get(B + "dev/relationship-types"));
        assertParity(call("search_knowledge", "brainId", "dev", "query", "GraphSpindle"),
                api().get(B + "dev/search?q=GraphSpindle"));
        assertParity(call("search_knowledge", "brainId", "dev", "query", "Neo4j", "types", List.of("Technology"),
                "limit", 3), api().get(B + "dev/search?q=Neo4j&types=Technology&limit=3"));
        assertParity(call("list_entities", "brainId", "dev"), api().get(B + "dev/entities"));
        assertParity(call("list_entities", "brainId", "dev", "type", "Project", "limit", 1),
                api().get(B + "dev/entities?type=Project&limit=1"));
        assertParity(call("get_entity", "brainId", "dev", "entityId", project), api().get(B + "dev/entities/" + project));
        assertParity(call("get_entity", "brainId", "dev", "entityId", project, "includeClaims", true, "maxClaims", 5),
                api().get(B + "dev/entities/" + project + "?include=claims&maxClaims=5"));
        assertParity(call("get_entity", "brainId", "dev", "entityId", project, "asOf", "2020-01-01"),
                api().get(B + "dev/entities/" + project + "?asOf=2020-01-01"));
        assertParity(call("get_entity_history", "brainId", "dev", "entityId", project),
                api().get(B + "dev/entities/" + project + "/history"));
        assertParity(call("get_neighbors", "brainId", "dev", "entityId", project, "depth", 2),
                api().get(B + "dev/entities/" + project + "/neighbors?depth=2"));
        assertParity(call("get_neighbors", "brainId", "dev", "entityId", project, "predicates", List.of("USES")),
                api().get(B + "dev/entities/" + project + "/neighbors?predicates=USES"));
        assertParity(call("list_claims", "brainId", "dev", "subjectId", project, "status", "ACTIVE"),
                api().get(B + "dev/claims?subjectId=" + project + "&status=ACTIVE"));
        assertParity(call("get_claim", "brainId", "dev", "claimId", claim), api().get(B + "dev/claims/" + claim));
        assertParity(call("get_claim_history", "brainId", "dev", "claimId", claim),
                api().get(B + "dev/claims/" + claim + "/history"));
        assertParity(call("list_conflicts", "brainId", "dev", "status", "OPEN"),
                api().get(B + "dev/conflicts?status=OPEN"));
    }

    @Test
    void claimWritesAndConflictResolutionMatchRest() {
        k.brain("w1");
        k.brain("w2");
        for (String b : List.of("w1", "w2")) {
            k.entity(b, "Project", "Ferrite");
            k.entity(b, "Technology", "SQLite");
            k.entity(b, "Technology", "Postgres");
        }
        String f1 = id("w1", "Ferrite");
        String f2 = id("w2", "Ferrite");

        // assert (REST in w1, MCP in w2)
        Response r1 = k.assertRelation("w1", f1, "USES", id("w1", "SQLite"));
        ToolResult m1 = call("assert_claim", "brainId", "w2", "subjectId", f2, "predicate", "USES", "objectId",
                id("w2", "SQLite"));
        assertThat(r1.status()).isEqualTo(201);
        assertThat(normalized(m1.result())).isEqualTo(normalized(r1.body()));
        String c1 = r1.body().get("claim").get("id").asString();
        String c2 = m1.result().get("claim").get("id").asString();

        // supersede
        Response s1 = api().post(B + "w1/claims/" + c1 + "/supersede", KnowledgeApi.json(Map.of("reason", "moved",
                "replacement", Map.of("objectId", id("w1", "Postgres")))));
        ToolResult s2 = call("supersede_claim", "brainId", "w2", "claimId", c2, "reason", "moved", "objectId",
                id("w2", "Postgres"));
        assertThat(s1.status()).as(s1.body().toString()).isEqualTo(200);
        assertThat(normalized(s2.result())).isEqualTo(normalized(s1.body()));
        assertThat(normalized(call("get_claim_history", "brainId", "w2", "claimId", c2).result()))
                .isEqualTo(normalized(api().get(B + "w1/claims/" + c1 + "/history").body()));

        // a contradiction of a ONE attribute opens a conflict; resolve it
        String v1 = k.value("w1", f1, "status", "ACTIVE");
        String v2 = call("assert_claim", "brainId", "w2", "subjectId", f2, "predicate", "status", "value",
                "ACTIVE").result().get("claim").get("id").asString();
        Response d1 = k.assertValue("w1", f1, "status", "ABANDONED");
        ToolResult d2 = call("assert_claim", "brainId", "w2", "subjectId", f2, "predicate", "status", "value",
                "ABANDONED");
        assertThat(normalized(d2.result())).isEqualTo(normalized(d1.body()));
        ToolResult conflicts2 = call("list_conflicts", "brainId", "w2", "status", "OPEN");
        Response conflicts1 = api().get(B + "w1/conflicts?status=OPEN");
        assertThat(normalized(conflicts2.result())).isEqualTo(normalized(conflicts1.body()));
        String x1 = conflicts1.body().get("items").get(0).get("id").asString();
        String x2 = conflicts2.result().get("items").get(0).get("id").asString();
        assertParity(call("get_conflict", "brainId", "w2", "conflictId", x2), api().get(B + "w2/conflicts/" + x2));
        Response res1 = resolve("w1", x1, "ACCEPT_INCOMING");
        ToolResult res2 = call("resolve_conflict", "brainId", "w2", "conflictId", x2, "resolution", "ACCEPT_INCOMING");
        assertThat(res1.status()).as(res1.body().toString()).isEqualTo(200);
        assertThat(normalized(res2.result())).isEqualTo(normalized(res1.body()));
        // resolving twice is the same state error over both
        assertErrorParity(call("resolve_conflict", "brainId", "w2", "conflictId", x2, "resolution", "KEEP_EXISTING"),
                resolve("w2", x2, "KEEP_EXISTING"));

        // retract: the accepted claim, and the superseded one (a state error over both)
        String a1 = d1.body().get("claim").get("id").asString();
        String a2 = d2.result().get("claim").get("id").asString();
        Response t1 = api().post(B + "w1/claims/" + a1 + "/retract", KnowledgeApi.json(Map.of("reason", "wrong")));
        ToolResult t2 = call("retract_claim", "brainId", "w2", "claimId", a2, "reason", "wrong");
        assertThat(t1.status()).as(t1.body().toString()).isEqualTo(200);
        assertThat(normalized(t2.result())).isEqualTo(normalized(t1.body()));
        assertErrorParity(call("retract_claim", "brainId", "w2", "claimId", v2, "reason", "wrong"),
                api().post(B + "w2/claims/" + v2 + "/retract", KnowledgeApi.json(Map.of("reason", "wrong"))));
        assertThat(v1).isNotEqualTo(v2);
        // the brains are mirror images
        assertThat(normalized(call("list_claims", "brainId", "w2").result()))
                .isEqualTo(normalized(api().get(B + "w1/claims").body()));
    }

    @Test
    void errorsCarryTheSameCodesAsRest() {
        // unknown brain
        assertErrorParity(call("get_brain", "brainId", "nope"), api().get(B + "nope"));
        assertErrorParity(call("search_knowledge", "brainId", "nope", "query", "x"), api().get(B + "nope/search?q=x"));
        assertErrorParity(call("list_claims", "brainId", "DEV"), api().get(B + "DEV/claims"));
        // unknown entity, claim, conflict, ingestion
        String missing = "01a219f8-c56d-7390-9a10-75f61367b8f5";
        assertErrorParity(call("get_entity", "brainId", "dev", "entityId", missing),
                api().get(B + "dev/entities/" + missing));
        assertErrorParity(call("get_claim", "brainId", "dev", "claimId", missing), api().get(B + "dev/claims/" + missing));
        assertErrorParity(call("get_conflict", "brainId", "dev", "conflictId", missing),
                api().get(B + "dev/conflicts/" + missing));
        assertErrorParity(call("get_ingestion", "brainId", "dev", "ingestionId", missing),
                api().get(B + "dev/ingestions/" + missing));
        // validation
        assertErrorParity(call("search_knowledge", "brainId", "dev", "query", "x", "limit", 500),
                api().get(B + "dev/search?q=x&limit=500"));
        String self = k.selfId("dev");
        assertErrorParity(call("assert_claim", "brainId", "dev", "subjectId", self, "predicate", "NOT_A_PREDICATE",
                "value", "x"), k.assertValue("dev", self, "NOT_A_PREDICATE", "x"));
        assertErrorParity(call("get_entity", "brainId", "dev", "entityId", self, "asOf", "yesterday"),
                api().get(B + "dev/entities/" + self + "?asOf=yesterday"));
        // invalid model output: the ingestion FAILS with the same code and status
        Response rest = ingest("dev", "garbage-output", "g-1");
        fake.load(fixture("garbage-output"));
        ToolResult m = call("remember", "brainId", "dev", "content", fixture("garbage-output").get("content").asString(),
                "requestId", "g-2");
        assertThat(rest.status()).isEqualTo(502);
        assertThat(m.error()).isTrue();
        assertThat(m.code()).isEqualTo("MALFORMED_MODEL_OUTPUT");
        assertThat(m.body().get("error").get("status").asInt()).isEqualTo(502);
        assertThat(m.result().get("status").asString()).isEqualTo("FAILED");
        // invalid source fields
        Map<String, Object> bad = Map.of("kind", "FILE", "locator", "a.md", "mediaType", "not a media type");
        assertErrorParity(call("remember", "brainId", "dev", "content", "x y z", "sources", List.of(bad)),
                ingest("dev", Map.of("content", "x y z", "sources", List.of(bad)), ""));
    }

    private String id(String brain, String name) {
        return api().get(B + brain + "/search?q=" + name).body().get("hits").get(0).get("entity").get("id").asString();
    }
}
