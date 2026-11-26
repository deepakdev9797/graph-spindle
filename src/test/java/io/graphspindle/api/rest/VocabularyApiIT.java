package io.graphspindle.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import io.graphspindle.support.ApiClient.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VocabularyApiIT extends AbstractNeo4jIT {

    static final String RECIPES = """
            {"profile":"core-v1",
             "disabledPredicates":["RELATED_TO"],
             "predicateOverrides":{"LICENSED_UNDER":{"cardinality":"MANY"}},
             "customEntityTypes":[{"name":"Recipe","description":"A cooking recipe","compatibilityGroup":null,
               "attributes":[{"name":"cuisine","valueType":"STRING","maxLength":50}]}],
             "customRelationPredicates":[{"name":"INGREDIENT_OF","description":"is an ingredient of",
               "sourceTypes":["Concept"],"targetTypes":["Recipe"],"cardinality":"MANY"}]}
            """;

    @BeforeEach
    void brains() {
        api().post("/api/v1/brains", BrainApiIT.brainJson("cook"));
        api().post("/api/v1/brains", BrainApiIT.brainJson("dev"));
    }

    @Test
    void getReturnsTheCoreViewByDefault() {
        Response r = api().get("/api/v1/brains/cook/vocabulary");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.etag()).isEqualTo("\"1\"");
        assertThat(r.body().get("profile").asString()).isEqualTo("core-v1");
        assertThat(r.body().get("entityTypes").size()).isEqualTo(15);
        assertThat(r.body().get("relationPredicates").size()).isEqualTo(28);
        assertThat(r.body().get("relationPredicates").toString()).contains("RELATED_TO");
        assertThat(api().get("/api/v1/brains/nope/vocabulary").status()).isEqualTo(404);
    }

    @Test
    void putValidatesAndBumpsConfigVersion() {
        Response r = api().put("/api/v1/brains/cook/vocabulary", RECIPES, "\"1\"");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("configVersion").asLong()).isEqualTo(2);
        assertThat(r.body().get("entityTypes").size()).isEqualTo(16);
        assertThat(r.body().get("entityTypes").toString()).contains("Recipe");
        assertThat(r.body().get("relationPredicates").toString()).contains("INGREDIENT_OF").doesNotContain("RELATED_TO");
        assertThat(r.body().get("attributePredicates").toString()).contains("cuisine");

        Response got = api().get("/api/v1/brains/cook/vocabulary");
        assertThat(got.body().get("configVersion").asLong()).isEqualTo(2);
        assertThat(got.body().get("config").get("customEntityTypes").get(0).get("name").asString())
                .isEqualTo("Recipe");
        // brain config keeps other settings
        assertThat(api().get("/api/v1/brains/cook").body().get("config").get("vocabulary").toString())
                .contains("INGREDIENT_OF");
        // the other brain is untouched
        Response dev = api().get("/api/v1/brains/dev/vocabulary");
        assertThat(dev.body().get("configVersion").asLong()).isEqualTo(1);
        assertThat(dev.body().get("entityTypes").toString()).doesNotContain("Recipe");
    }

    @Test
    void invalidVocabularyIsRejectedWithIssues() {
        Response reserved = api().put("/api/v1/brains/cook/vocabulary",
                "{\"customEntityTypes\":[{\"name\":\"Claim\",\"description\":\"x\"}]}", null);
        assertThat(reserved.status()).isEqualTo(400);
        assertThat(reserved.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(reserved.body().get("issues").toString()).contains("RESERVED_NAME");

        Response noPerson = api().put("/api/v1/brains/cook/vocabulary",
                "{\"enabledEntityTypes\":[\"Project\",\"Technology\"]}", null);
        assertThat(noPerson.status()).isEqualTo(400);
        assertThat(noPerson.body().get("issues").toString()).contains("CANNOT_DISABLE");

        Response badRegex = api().put("/api/v1/brains/cook/vocabulary",
                "{\"customRelationPredicates\":[{\"name\":\"bad name\",\"description\":\"x\","
                        + "\"sourceTypes\":[\"Person\"],\"targetTypes\":[\"Person\"],\"cardinality\":\"MANY\"}]}", null);
        assertThat(badRegex.status()).isEqualTo(400);

        Response unknownField = api().put("/api/v1/brains/cook/vocabulary", "{\"enabledTypes\":[\"Person\"]}", null);
        assertThat(unknownField.status()).isEqualTo(400);

        assertThat(api().get("/api/v1/brains/cook").body().get("configVersion").asLong()).isEqualTo(1);
    }

    @Test
    void staleVersionIsPreconditionFailed() {
        assertThat(api().put("/api/v1/brains/cook/vocabulary", RECIPES, "\"1\"").status()).isEqualTo(200);
        Response stale = api().put("/api/v1/brains/cook/vocabulary", "{}", "\"1\"");
        assertThat(stale.status()).isEqualTo(412);
    }
}
