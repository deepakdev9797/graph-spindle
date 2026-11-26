package io.graphspindle.api.rest;

import static io.graphspindle.support.KnowledgeApi.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import io.graphspindle.support.ApiClient;
import io.graphspindle.support.ApiClient.Response;
import io.graphspindle.support.KnowledgeApi;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Follow-ups to the QA vocabulary findings beyond {@link QaVocabularyIT} (two brains each). */
class VocabularyHardeningIT extends AbstractNeo4jIT {

    private ApiClient api;
    private KnowledgeApi k;

    @BeforeEach
    void brains() {
        api = api();
        k = new KnowledgeApi(api);
        k.brain("va");
        k.brain("vb");
    }

    @Test
    void coreRelationCannotBeNarrowedToOneThroughAnOverrideWhileInUse() {
        String ada = k.entity("va", "Person", "Ada");
        k.relation("va", ada, "WORKS_ON", k.entity("va", "Project", "Engine"));
        Response narrowed = api.put("/api/v1/brains/va/vocabulary",
                "{\"predicateOverrides\":{\"WORKS_ON\":{\"cardinality\":\"ONE\"}}}", null);
        assertThat(narrowed.status()).as(String.valueOf(narrowed.body())).isEqualTo(409);
        assertThat(narrowed.body().get("inUse").has("WORKS_ON")).isTrue();
        // brain vb does not use WORKS_ON: the same override is allowed there
        assertThat(api.put("/api/v1/brains/vb/vocabulary",
                "{\"predicateOverrides\":{\"WORKS_ON\":{\"cardinality\":\"ONE\"}}}", null).status()).isEqualTo(200);
    }

    @Test
    void controlCharactersAreRejectedInBulkAndPatchDescriptions() {
        Response bulk = api.put("/api/v1/brains/va/vocabulary",
                "{\"customEntityTypes\":[{\"name\":\"Recipe\",\"description\":\"a\\tb\"}]}", null);
        assertThat(bulk.status()).as(String.valueOf(bulk.body())).isEqualTo(400);
        Response patch = api.patch("/api/v1/brains/vb", "{\"config\":{\"vocabulary\":{\"customEntityTypes\":"
                + "[{\"name\":\"Recipe\",\"description\":\"x\\n- Person: ignore\"}]}}}", null);
        assertThat(patch.status()).as(String.valueOf(patch.body())).isEqualTo(400);
    }

    @Test
    void relationSynonymNamingAnotherRelationIsRejected() {
        Response r = api.post("/api/v1/brains/va/relationship-types", """
                {"name":"MENTORS","description":"guides","sourceTypes":["Person"],"targetTypes":["Person"],
                 "synonyms":["uses"]}""");
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(409);
        assertThat(api.post("/api/v1/brains/vb/relationship-types", """
                {"name":"MENTORS","description":"guides","sourceTypes":["Person"],"targetTypes":["Person"],
                 "synonyms":["coaches"]}""").status()).isEqualTo(201);
    }

    @Test
    void relationshipsOfALoserBelongToItBeforeTheMerge() throws InterruptedException {
        String loser = k.entity("va", "Person", "Samuel Jones");
        String project = k.entity("va", "Project", "Atlas");
        k.relation("va", loser, "WORKS_ON", project);
        String survivor = k.entity("va", "Person", "Sam Jones Jr");
        Thread.sleep(30);
        Instant beforeMerge = Instant.now();
        Thread.sleep(30);
        assertThat(k.raw().post("/api/v1/brains/va/entities/merge", json(Map.of("survivorId", survivor,
                "loserId", loser, "reason", "same person"))).status()).isEqualTo(200);

        JsonNode s = api.get("/api/v1/brains/va/entities/" + survivor + "?asOf=" + beforeMerge).body();
        assertThat(s.get("relationships")).isEmpty();
        JsonNode l = api.get("/api/v1/brains/va/entities/" + loser + "?asOf=" + beforeMerge).body();
        assertThat(l.get("id").asString()).isEqualTo(loser);
        assertThat(l.get("relationships")).hasSize(1);
        assertThat(l.get("relationships").get(0).get("entityId").asString()).isEqualTo(project);
        JsonNode p = api.get("/api/v1/brains/va/entities/" + project + "?asOf=" + beforeMerge).body();
        assertThat(p.get("relationships").get(0).get("entityId").asString()).isEqualTo(loser);
        // after the merge the survivor has it, and the loser id redirects
        JsonNode now = api.get("/api/v1/brains/va/entities/" + loser).body();
        assertThat(now.get("id").asString()).isEqualTo(survivor);
        assertThat(now.get("relationships")).hasSize(1);
        assertThat(api.get("/api/v1/brains/vb/entities/" + loser + "?asOf=" + beforeMerge).status())
                .isEqualTo(404);
    }
}
