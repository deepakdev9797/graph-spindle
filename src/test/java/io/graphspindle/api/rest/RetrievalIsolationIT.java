package io.graphspindle.api.rest;

import static io.graphspindle.support.KnowledgeApi.json;
import static io.graphspindle.support.KnowledgeApi.texts;
import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import io.graphspindle.support.ApiClient.Response;
import io.graphspindle.support.KnowledgeApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M5 acceptance: retrieval and manual writes never cross brains, even with identical names. */
class RetrievalIsolationIT extends AbstractNeo4jIT {

    KnowledgeApi k;
    final Map<String, Ids> ids = new java.util.HashMap<>();

    record Ids(String gs, String neo4j, String uses, String conflict) {
    }

    @BeforeEach
    void twoIdenticalBrains() {
        k = new KnowledgeApi(api());
        for (String b : List.of("brain-a", "brain-b")) {
            k.brain(b);
            String gs = k.described(b, "Project", "GraphSpindle", "Graph engine", "gspin");
            String neo4j = k.entity(b, "Technology", "Neo4j");
            String uses = k.relation(b, gs, "USES", neo4j);
            k.relation(b, gs, "LICENSED_UNDER", k.entity(b, "Standard", "Apache License 2.0"));
            String conflict = k.assertRelation(b, gs, "LICENSED_UNDER", k.entity(b, "Standard", "MIT License"))
                    .body().get("conflictId").asString();
            ids.put(b, new Ids(gs, neo4j, uses, conflict));
        }
    }

    List<String> allIdsIn(JsonNode node) {
        List<String> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private void collect(JsonNode n, List<String> out) {
        if (n == null) {
            return;
        }
        if (n.isString() && n.asString().matches("[0-9a-f]{8}-[0-9a-f]{4}-.*")) {
            out.add(n.asString());
        }
        n.forEach(c -> collect(c, out)); // array elements and object values
    }

    @Test
    void searchContextAndNeighboursStayInTheBrain() {
        Ids a = ids.get("brain-a");
        Ids b = ids.get("brain-b");
        List<String> bIds = allIdsIn(k.get("/api/v1/brains/brain-b/entities?status=ACTIVE"));
        bIds.addAll(List.of(b.uses(), b.conflict()));
        JsonNode search = k.get("/api/v1/brains/brain-a/search?q=graphspindle%20neo4j%20gspin");
        assertThat(texts(search.get("hits"), "entity.id")).contains(a.gs()).doesNotContainAnyElementsOf(bIds);
        JsonNode context = k.get("/api/v1/brains/brain-a/entities/" + a.gs() + "?include=claims");
        assertThat(allIdsIn(context)).contains(a.uses()).doesNotContainAnyElementsOf(bIds);
        JsonNode hood = k.get("/api/v1/brains/brain-a/entities/" + a.gs() + "/neighbors?depth=2");
        assertThat(allIdsIn(hood)).contains(a.neo4j()).doesNotContainAnyElementsOf(bIds);
        assertThat(allIdsIn(k.get("/api/v1/brains/brain-a/conflicts"))).contains(a.conflict())
                .doesNotContainAnyElementsOf(bIds);
        assertThat(allIdsIn(k.get("/api/v1/brains/brain-a/claims"))).doesNotContainAnyElementsOf(bIds);
    }

    @Test
    void idsFromAnotherBrainAreNotFound() {
        Ids a = ids.get("brain-a");
        Ids b = ids.get("brain-b");
        String base = "/api/v1/brains/brain-a";
        List<Response> reads = List.of(k.raw().get(base + "/entities/" + b.gs()),
                k.raw().get(base + "/entities/" + b.gs() + "?include=claims"),
                k.raw().get(base + "/entities/" + b.gs() + "?asOf=2999-01-01"),
                k.raw().get(base + "/entities/" + b.gs() + "/history"),
                k.raw().get(base + "/entities/" + b.gs() + "/neighbors"),
                k.raw().get(base + "/entities/" + b.gs() + "/aliases"),
                k.raw().get(base + "/claims/" + b.uses()),
                k.raw().get(base + "/claims/" + b.uses() + "/history"),
                k.raw().get(base + "/conflicts/" + b.conflict()));
        reads.forEach(r -> {
            assertThat(r.status()).isEqualTo(404);
            assertThat(r.code()).isEqualTo("NOT_FOUND");
        });
        List<Response> writes = List.of(
                k.raw().post(base + "/claims/" + b.uses() + "/retract", json(Map.of("reason", "x"))),
                k.raw().post(base + "/claims/" + b.uses() + "/supersede", json(Map.of("reason", "x", "replacement",
                        Map.of("objectId", a.neo4j())))),
                k.assertRelation("brain-a", a.gs(), "USES", b.neo4j()),
                k.assertRelation("brain-a", b.gs(), "USES", a.neo4j()),
                k.raw().patch(base + "/entities/" + b.gs(), json(Map.of("description", "x")), null),
                k.raw().delete(base + "/entities/" + b.gs()),
                k.raw().post(base + "/entities/" + b.gs() + "/aliases", json(Map.of("alias", "x"))),
                k.raw().post(base + "/entities/merge", json(Map.of("survivorId", a.gs(), "loserId", b.gs()))),
                k.raw().post(base + "/conflicts/" + b.conflict() + "/resolve", json(Map.of("resolution",
                        "KEEP_EXISTING"))));
        writes.forEach(r -> assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(404));
        // brain B is unchanged
        JsonNode bConflict = k.get("/api/v1/brains/brain-b/conflicts/" + b.conflict());
        assertThat(bConflict.get("status").asString()).isEqualTo("OPEN");
        assertThat(k.get("/api/v1/brains/brain-b/claims/" + b.uses()).get("claim").get("status").asString())
                .isEqualTo("ACTIVE");
        assertThat(k.get("/api/v1/brains/brain-b/entities/" + b.gs() + "?include=claims").get("entity").get("description").asString())
                .isEqualTo("Graph engine");
        assertThat(count("MATCH (a {brainId: 'brain-a'})-[r]-(b {brainId: 'brain-b'}) RETURN count(r)", Map.of()))
                .isZero();
        assertThat(k.raw().get("/api/v1/brains/nope/search?q=x").code()).isEqualTo("BRAIN_NOT_FOUND");
    }
}
