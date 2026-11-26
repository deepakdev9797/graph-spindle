package io.graphspindle.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import io.graphspindle.support.ApiClient.Response;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BrainConfigIT extends AbstractNeo4jIT {

    @BeforeEach
    void brains() {
        api().post("/api/v1/brains", BrainApiIT.brainJson("cfg"));
        api().post("/api/v1/brains", BrainApiIT.brainJson("other"));
    }

    @Test
    void updateWithMatchingVersionBumpsIt() {
        Response r = api().patch("/api/v1/brains/cfg",
                "{\"name\":\"Renamed\",\"config\":{\"conflictPolicy\":\"ALWAYS_FLAG\",\"minConfidence\":0.6}}",
                "\"1\"");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.etag()).isEqualTo("\"2\"");
        assertThat(r.body().get("configVersion").asLong()).isEqualTo(2);
        assertThat(r.body().get("name").asString()).isEqualTo("Renamed");
        assertThat(r.body().get("config").get("conflictPolicy").asString()).isEqualTo("ALWAYS_FLAG");
        assertThat(r.body().get("config").get("minConfidence").asDouble()).isEqualTo(0.6);
        // defaults fill the rest
        assertThat(r.body().get("config").get("maxLlmConfidence").asDouble()).isEqualTo(0.95);

        Response got = api().get("/api/v1/brains/cfg");
        assertThat(got.body().get("config").get("conflictPolicy").asString()).isEqualTo("ALWAYS_FLAG");
        assertThat(api().get("/api/v1/brains/other").body().get("configVersion").asLong()).isEqualTo(1);
    }

    @Test
    void staleIfMatchIsPreconditionFailed() {
        assertThat(api().patch("/api/v1/brains/cfg", "{\"name\":\"v2\"}", "\"1\"").status()).isEqualTo(200);
        Response stale = api().patch("/api/v1/brains/cfg", "{\"name\":\"lost update\"}", "\"1\"");
        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.code()).isEqualTo("PRECONDITION_FAILED");
        assertThat(stale.body().get("configVersion").asLong()).isEqualTo(2);
        assertThat(api().get("/api/v1/brains/cfg").body().get("name").asString()).isEqualTo("v2");
        assertThat(api().patch("/api/v1/brains/cfg", "{\"name\":\"x\"}", "W/\"2\"").status()).isEqualTo(200);
        assertThat(api().patch("/api/v1/brains/cfg", "{\"name\":\"x\"}", "garbage").status()).isEqualTo(400);
    }

    @Test
    void concurrentUpdatesWithSameVersionOnlyOneWins() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 8; i++) {
                int n = i;
                futures.add(pool.submit(() -> api().patch("/api/v1/brains/cfg", "{\"name\":\"n" + n + "\"}", "\"1\"")
                        .status()));
            }
            int ok = 0;
            for (var f : futures) {
                int s = f.get();
                assertThat(s).isIn(200, 412);
                ok += s == 200 ? 1 : 0;
            }
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(api().get("/api/v1/brains/cfg").body().get("configVersion").asLong()).isEqualTo(2);
    }

    @Test
    void invalidConfigIsRejectedAndNothingChanges() {
        Response r = api().patch("/api/v1/brains/cfg", "{\"config\":{\"maxLlmConfidence\":0}}", null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(r.body().get("issues").toString()).contains("maxLlmConfidence");
        Response badVocab = api().patch("/api/v1/brains/cfg",
                "{\"config\":{\"vocabulary\":{\"enabledEntityTypes\":[\"Project\"]}}}", null);
        assertThat(badVocab.status()).isEqualTo(400);
        assertThat(api().get("/api/v1/brains/cfg").body().get("configVersion").asLong()).isEqualTo(1);
        assertThat(count("MATCH (b:Brain {id:'cfg'}) RETURN b.configVersion", Map.of())).isEqualTo(1);
    }

    @Test
    void unknownBrainUpdateIsNotFound() {
        assertThat(api().patch("/api/v1/brains/ghost", "{\"name\":\"x\"}", null).status()).isEqualTo(404);
    }
}
