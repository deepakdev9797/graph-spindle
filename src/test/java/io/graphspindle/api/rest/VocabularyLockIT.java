package io.graphspindle.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import io.graphspindle.support.ApiClient;
import io.graphspindle.support.ApiClient.Response;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** {@code graphspindle.vocabulary.api-mutable=false} makes every vocabulary write 403 (ADR-025). */
@TestPropertySource(properties = "graphspindle.vocabulary.api-mutable=false")
class VocabularyLockIT extends AbstractNeo4jIT {

    @Test
    void mutatingEndpointsAreForbiddenAndReadsStillWork() {
        ApiClient api = api();
        assertThat(api.post("/api/v1/brains", BrainApiIT.brainJson("locked")).status()).isEqualTo(201);

        Response create = api.post("/api/v1/brains/locked/node-types", NodeTypeApiIT.RECIPE);
        assertThat(create.status()).isEqualTo(403);
        assertThat(create.code()).isEqualTo("FORBIDDEN");
        assertThat(create.body().get("detail").asString()).contains("api-mutable=false");
        assertThat(api.put("/api/v1/brains/locked/node-types/Place", "{\"enabled\":false}", null).status())
                .isEqualTo(403);
        assertThat(api.delete("/api/v1/brains/locked/node-types/Place").status()).isEqualTo(403);
        assertThat(api.post("/api/v1/brains/locked/relationship-types", """
                {"name":"MENTORS","description":"m","sourceTypes":["Person"],"targetTypes":["Person"]}""").status())
                .isEqualTo(403);
        assertThat(api.put("/api/v1/brains/locked/relationship-types/USES", "{\"enabled\":false}", null).status())
                .isEqualTo(403);
        assertThat(api.delete("/api/v1/brains/locked/relationship-types/USES").status()).isEqualTo(403);
        assertThat(api.put("/api/v1/brains/locked/vocabulary", "{\"disabledPredicates\":[\"RELATED_TO\"]}", null)
                .status()).isEqualTo(403);
        assertThat(api.patch("/api/v1/brains/locked",
                "{\"config\":{\"vocabulary\":{\"disabledPredicates\":[\"RELATED_TO\"]}}}", null).status())
                .isEqualTo(403);
        assertThat(api.post("/api/v1/brains", "{\"id\":\"locked2\",\"name\":\"x\",\"config\":{\"vocabulary\":"
                + "{\"disabledPredicates\":[\"RELATED_TO\"]}}}").status()).isEqualTo(403);

        // non-vocabulary config changes and reads are unaffected
        assertThat(api.patch("/api/v1/brains/locked", "{\"config\":{\"minConfidence\":0.6}}", null).status())
                .isEqualTo(200);
        assertThat(api.get("/api/v1/brains/locked/node-types").status()).isEqualTo(200);
        assertThat(api.get("/api/v1/node-types").status()).isEqualTo(200);
        assertThat(api.get("/api/v1/brains/locked/relationship-types/USES").status()).isEqualTo(200);
        assertThat(api.get("/api/v1/brains/locked").body().get("configVersion").asLong()).isEqualTo(2);
    }
}
