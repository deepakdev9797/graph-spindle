package io.graphspindle.api.rest;

import static io.graphspindle.support.KnowledgeApi.json;
import static io.graphspindle.support.KnowledgeApi.texts;
import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import io.graphspindle.support.KnowledgeApi;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M5 acceptance: full entity merge (SCHEMA §9.5). */
class MergeIT extends AbstractNeo4jIT {

    KnowledgeApi k;
    String survivor;
    String loser;
    String neo4j;
    String apache;
    String mit;
    String alice;
    String survivorUses;
    String loserUses;
    String loserLicense;
    String aliceWorksOn;

    @BeforeEach
    void setUp() {
        k = new KnowledgeApi(api());
        k.brain("dev");
        k.brain("other");
        survivor = k.entity("dev", "Project", "GraphSpindle");
        loser = k.entity("dev", "Project", "Graph Spindle", "gs");
        neo4j = k.entity("dev", "Technology", "Neo4j");
        apache = k.entity("dev", "Standard", "Apache License 2.0");
        mit = k.entity("dev", "Standard", "MIT License");
        alice = k.entity("dev", "Person", "Alice Chen");
        survivorUses = k.relation("dev", survivor, "USES", neo4j);
        loserUses = k.relation("dev", loser, "USES", neo4j);
        k.relation("dev", survivor, "LICENSED_UNDER", apache);
        loserLicense = k.relation("dev", loser, "LICENSED_UNDER", mit);
        aliceWorksOn = k.relation("dev", alice, "WORKS_ON", loser);
        k.value("dev", loser, "status", "ACTIVE");
        String other = k.entity("other", "Project", "Graph Spindle", "gs");
        k.relation("other", other, "USES", k.entity("other", "Technology", "Neo4j"));
    }

    JsonNode merge() {
        var r = k.raw().post("/api/v1/brains/dev/entities/merge", json(Map.of("survivorId", survivor, "loserId", loser,
                "reason", "spacing variant")));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.body();
    }

    @Test
    void claimsAreRepointedAndDuplicatesReinforced() {
        JsonNode m = merge();
        assertThat(m.get("claimsRepointed").asInt()).isEqualTo(4);
        assertThat(m.get("claimsReinforced").asInt()).isEqualTo(1);
        assertThat(k.get("/api/v1/brains/dev/claims/" + loserUses).get("claim").get("status").asString())
                .isEqualTo("SUPERSEDED");
        assertThat(k.get("/api/v1/brains/dev/claims/" + loserUses).get("supersededBy").get(0).asString())
                .isEqualTo(survivorUses);
        assertThat(k.get("/api/v1/brains/dev/claims?subjectId=" + survivor + "&predicate=USES&status=ACTIVE")
                .get("items").size()).isEqualTo(1);
        JsonNode works = k.get("/api/v1/brains/dev/claims/" + aliceWorksOn).get("claim");
        assertThat(works.get("objectId").asString()).isEqualTo(survivor);
        assertThat(works.get("status").asString()).isEqualTo("ACTIVE");
        JsonNode n = k.get("/api/v1/brains/dev/entities/" + survivor + "/neighbors");
        assertThat(texts(n.get("nodes"), "entity.name")).containsExactlyInAnyOrder("Neo4j", "Apache License 2.0",
                "Alice Chen");
        assertThat(k.get("/api/v1/brains/dev/entities/" + survivor).get("entity").get("attributes").get("status")
                .asString()).isEqualTo("ACTIVE");
        assertThat(count("MATCH (n:Entity {brainId: 'dev', id: $id}) RETURN size([k IN keys(n) WHERE k STARTS WITH"
                + " 'attr_'])", Map.of("id", loser))).isZero();
        assertThat(k.get("/api/v1/admin/brains/dev/projection-check").get("consistent").asBoolean()).isTrue();
    }

    @Test
    void aSlotCollisionOpensAContradiction() {
        JsonNode m = merge();
        assertThat(m.get("claimsDisputed").asInt()).isEqualTo(1);
        String conflictId = m.get("conflictIds").get(0).asString();
        JsonNode c = k.get("/api/v1/brains/dev/conflicts/" + conflictId);
        assertThat(c.get("kind").asString()).isEqualTo("CONTRADICTION");
        assertThat(texts(c.get("claims"), "claim.id")).contains(loserLicense);
        assertThat(k.get("/api/v1/brains/dev/claims/" + loserLicense).get("claim").get("status").asString())
                .isEqualTo("DISPUTED");
        JsonNode n = k.get("/api/v1/brains/dev/entities/" + survivor + "/neighbors?predicates=LICENSED_UNDER");
        assertThat(texts(n.get("nodes"), "entity.name")).containsExactly("Apache License 2.0");
    }

    @Test
    void aliasesMoveAndTheLoserIsTombstonedAndRedirects() {
        merge();
        assertThat(texts(k.get("/api/v1/brains/dev/entities/" + survivor + "/aliases"), "normalized"))
                .contains("graphspindle", "graph spindle", "gs");
        JsonNode redirected = k.get("/api/v1/brains/dev/entities/" + loser);
        assertThat(redirected.get("redirectedFrom").asString()).isEqualTo(loser);
        assertThat(redirected.get("entity").get("id").asString()).isEqualTo(survivor);
        JsonNode raw = k.get("/api/v1/brains/dev/entities?status=MERGED").get("items").get(0);
        assertThat(raw.get("id").asString()).isEqualTo(loser);
        assertThat(raw.get("mergedIntoId").asString()).isEqualTo(survivor);
        assertThat(count("MATCH (:Entity {brainId: 'dev', id: $l})-[r:MERGED_INTO {brainId: 'dev'}]->"
                + "(:Entity {id: $s}) RETURN count(r)", Map.of("l", loser, "s", survivor))).isEqualTo(1);
        assertThat(texts(k.get("/api/v1/brains/dev/search?q=gs").get("hits"), "entity.id")).containsExactly(survivor);
        // writes to the loser go to the survivor; merging it again is a state conflict
        var viaLoser = k.assertValue("dev", loser, "startDate", "2026-01");
        assertThat(viaLoser.body().get("claim").get("subjectId").asString()).isEqualTo(survivor);
        assertThat(k.raw().post("/api/v1/brains/dev/entities/merge", json(Map.of("survivorId", survivor, "loserId",
                loser))).code()).isEqualTo("CONFLICT_STATE");
        // the other brain is untouched
        assertThat(k.get("/api/v1/brains/other/entities?status=MERGED").get("items").size()).isZero();
        assertThat(texts(k.get("/api/v1/brains/other/search?q=gs").get("hits"), "entity.name"))
                .containsExactly("Graph Spindle");
    }

    @Test
    void incompatibleTypesAndSelfMergeAreRejected() {
        assertThat(k.raw().post("/api/v1/brains/dev/entities/merge", json(Map.of("survivorId", survivor, "loserId",
                neo4j))).status()).isEqualTo(400);
        assertThat(k.raw().post("/api/v1/brains/dev/entities/merge", json(Map.of("survivorId", survivor, "loserId",
                survivor))).status()).isEqualTo(400);
        assertThat(k.raw().post("/api/v1/brains/dev/entities/merge", json(Map.of("survivorId", k.selfId("dev"),
                "loserId", alice))).status()).isEqualTo(200);
    }
}
