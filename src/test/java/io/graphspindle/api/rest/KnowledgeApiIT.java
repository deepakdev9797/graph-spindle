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

/** M5 acceptance: the README example built manually through REST, no LLM (IMPLEMENTATION_PLAN M5). */
class KnowledgeApiIT extends AbstractNeo4jIT {

    KnowledgeApi k;
    String self;
    String gs;
    String java;
    String neo4j;
    String springAi;
    String apache;

    @BeforeEach
    void readmeExample() {
        k = new KnowledgeApi(api());
        k.brain("dev");
        k.brain("other");
        self = k.selfId("dev");
        gs = k.described("dev", "Project", "GraphSpindle", "Knowledge graph engine being built by the speaker.");
        java = k.entity("dev", "Technology", "Java");
        neo4j = k.entity("dev", "Technology", "Neo4j");
        springAi = k.entity("dev", "Technology", "Spring AI");
        apache = k.entity("dev", "Standard", "Apache License 2.0", "Apache 2.0", "Apache-2.0");
        k.relation("dev", self, "WORKS_ON", gs);
        k.relation("dev", gs, "WRITTEN_IN", java);
        k.relation("dev", gs, "USES", neo4j);
        k.relation("dev", gs, "USES", springAi);
        k.relation("dev", gs, "LICENSED_UNDER", apache);
        k.value("dev", java, "category", "LANGUAGE");
        // the same names in another brain
        k.entity("other", "Project", "GraphSpindle");
    }

    @Test
    void entitiesAreManualAndIdempotentByKey() {
        var again = k.raw().post("/api/v1/brains/dev/entities", json(Map.of("type", "Project", "name", "graphspindle")));
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("created").asBoolean()).isFalse();
        assertThat(again.body().get("entity").get("id").asString()).isEqualTo(gs);
        var distinct = k.raw().post("/api/v1/brains/dev/entities", json(Map.of("type", "Project", "name",
                "GraphSpindle", "distinct", true)));
        assertThat(distinct.status()).isEqualTo(201);
        assertThat(distinct.body().get("entity").get("canonicalKey").asString()).isEqualTo("project:graphspindle~2");

        JsonNode e = k.get("/api/v1/brains/dev/entities/" + gs).get("entity");
        assertThat(e.get("origin").asString()).isEqualTo("MANUAL");
        assertThat(e.get("descriptionLocked").asBoolean()).isTrue();
        assertThat(k.get("/api/v1/brains/dev/entities/" + java).get("entity").get("attributes").get("category")
                .asString()).isEqualTo("LANGUAGE");

        JsonNode page = k.get("/api/v1/brains/dev/entities?limit=3");
        assertThat(page.get("items").size()).isEqualTo(3);
        JsonNode rest = k.get("/api/v1/brains/dev/entities?limit=50&cursor=" + page.get("next").asString());
        assertThat(rest.get("items").size()).isEqualTo(4); // 7 entities incl. self and the distinct one
        assertThat(rest.get("next").isNull()).isTrue();
        assertThat(k.get("/api/v1/brains/dev/entities?type=Technology").get("items").size()).isEqualTo(3);
    }

    @Test
    void neighboursReturnTheAssertedRelations() {
        JsonNode n = k.get("/api/v1/brains/dev/entities/" + gs + "/neighbors");
        assertThat(texts(n.get("nodes"), "entity.name")).containsExactlyInAnyOrder("Apache License 2.0", "Java",
                "Me", "Neo4j", "Spring AI");
        assertThat(texts(n.get("edges"), "predicate")).containsExactlyInAnyOrder("WORKS_ON", "WRITTEN_IN", "USES",
                "USES", "LICENSED_UNDER");
        n.get("edges").forEach(edge -> assertThat(edge.get("claimId").asString()).isNotBlank());
        JsonNode uses = k.get("/api/v1/brains/dev/entities/" + gs + "/neighbors?predicates=USES,WRITTEN_IN");
        assertThat(texts(uses.get("nodes"), "entity.name")).containsExactlyInAnyOrder("Java", "Neo4j", "Spring AI");
        JsonNode two = k.get("/api/v1/brains/dev/entities/" + self + "/neighbors?depth=2");
        assertThat(texts(two.get("nodes"), "entity.name")).contains("GraphSpindle", "Neo4j", "Java");
        assertThat(two.get("nodes").get(0).get("distance").asInt()).isEqualTo(1);
    }

    @Test
    void searchFindsByFulltextAndByAlias() {
        JsonNode fulltext = k.get("/api/v1/brains/dev/search?q=graph%20spindle");
        assertThat(fulltext.get("hits").get(0).get("entity").get("id").asString()).isEqualTo(gs);
        assertThat(texts(fulltext.get("hits").get(0).get("matchedBy"), null)).contains("fulltext");
        assertThat(fulltext.get("untrusted").asBoolean()).isTrue();

        JsonNode alias = k.get("/api/v1/brains/dev/search?q=apache-2.0");
        JsonNode top = alias.get("hits").get(0);
        assertThat(top.get("entity").get("id").asString()).isEqualTo(apache);
        assertThat(texts(top.get("matchedBy"), null)).contains("alias");
        assertThat(top.get("score").asDouble()).isEqualTo(1.0);

        // claim statements are searchable too, and the type filter applies
        JsonNode viaClaim = k.get("/api/v1/brains/dev/search?q=written&types=Project");
        assertThat(texts(viaClaim.get("hits"), "entity.id")).containsExactly(gs);
        assertThat(k.raw().get("/api/v1/brains/dev/search?q=x&types=Spaceship").status()).isEqualTo(400);
    }

    @Test
    void entityContextAndClaimProvenance() {
        JsonNode c = k.get("/api/v1/brains/dev/entities/" + gs);
        assertThat(c.get("redirectedFrom").isNull()).isTrue();
        assertThat(texts(c.get("claims"), "claim.predicate")).containsExactlyInAnyOrder("WORKS_ON", "WRITTEN_IN",
                "USES", "USES", "LICENSED_UNDER");
        assertThat(c.get("text").asString()).contains("GraphSpindle", "untrusted").contains("Neo4j");
        assertThat(texts(k.get("/api/v1/brains/dev/entities/" + apache + "/aliases"), "normalized"))
                .containsExactly("apache 2.0", "apache license 2.0", "apache-2.0");

        String usesNeo4j = k.get("/api/v1/brains/dev/claims?subjectId=" + gs + "&predicate=USES&limit=10")
                .get("items").get(0).get("id").asString();
        JsonNode detail = k.get("/api/v1/brains/dev/claims/" + usesNeo4j);
        assertThat(detail.get("claim").get("origin").asString()).isEqualTo("MANUAL");
        assertThat(detail.get("claim").get("confidence").asDouble()).isEqualTo(1.0);
        assertThat(detail.get("claim").get("subjectName").asString()).isEqualTo("GraphSpindle");
        assertThat(detail.get("evidence").size()).isZero();
        assertThat(k.get("/api/v1/brains/dev/sources").get("items").size()).isZero();
        assertThat(k.get("/api/v1/admin/brains/dev/projection-check").get("consistent").asBoolean()).isTrue();
    }

    @Test
    void invalidManualClaimsAreRejected() {
        assertThat(k.assertRelation("dev", gs, "INVENTED", java).status()).isEqualTo(400);
        assertThat(k.assertRelation("dev", java, "WRITTEN_IN", gs).status()).isEqualTo(400); // type pair
        assertThat(k.assertRelation("dev", gs, "USES", gs).status()).isEqualTo(400); // self-loop
        assertThat(k.assertValue("dev", java, "category", "SPACESHIP").status()).isEqualTo(400);
        assertThat(k.assertValue("dev", java, "USES", "x").status()).isEqualTo(400);
        assertThat(k.assertRelation("dev", gs, "USES", "0190a3c4-0000-7000-8000-000000000000").status())
                .isEqualTo(404);
        assertThat(k.raw().post("/api/v1/brains/dev/entities", json(Map.of("type", "Spaceship", "name", "x")))
                .status()).isEqualTo(400);
        assertThat(k.raw().post("/api/v1/brains/dev/entities", json(Map.of("type", "Person", "name", "@self")))
                .status()).isEqualTo(400);
        assertThat(k.raw().post("/api/v1/brains/dev/entities", json(Map.of("type", "Concept", "name",
                "AKIAIOSFODNN7EXAMPLE"))).status()).isEqualTo(400);
        // a duplicate assertion is a reinforcement, not a second claim
        var again = k.assertRelation("dev", gs, "USES", neo4j);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("outcome").asString()).isEqualTo("REINFORCED");
    }
}
