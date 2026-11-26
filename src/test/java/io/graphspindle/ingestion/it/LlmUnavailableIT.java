package io.graphspindle.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import io.graphspindle.support.ApiClient.Response;
import io.graphspindle.support.KnowledgeApi;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * M6 acceptance (LlmDisabledIT): the default context has no chat model ({@code spring.ai.model.chat=none}):
 * ingestion answers 503 LLM_UNAVAILABLE and records nothing, deterministic CRUD and retrieval work.
 */
class LlmUnavailableIT extends AbstractNeo4jIT {

    @Test
    void ingestIs503AndEverythingElseWorks() {
        KnowledgeApi k = new KnowledgeApi(api());
        k.brain("dev");
        k.brain("other");
        k.entity("dev", "Project", "GraphSpindle");
        Response r = api().post("/api/v1/brains/dev/ingestions", KnowledgeApi.json(Map.of("content",
                "I am building GraphSpindle in Java.")));
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.code()).isEqualTo("LLM_UNAVAILABLE");
        assertThat(count("MATCH (i:Ingestion) RETURN count(i)", Map.of())).isZero();
        assertThat(api().get("/api/v1/brains/dev/search?q=graphspindle").status()).isEqualTo(200);
        assertThat(api().get("/api/v1/brains/dev/search?q=graphspindle").body().get("hits")).hasSize(1);
        assertThat(api().get("/api/v1/brains/other/search?q=graphspindle").body().get("hits")).isEmpty();
        // request validation still comes first
        assertThat(api().post("/api/v1/brains/dev/ingestions", KnowledgeApi.json(Map.of("content", ""))).status())
                .isEqualTo(400);
        assertThat(api().post("/api/v1/brains/nope/ingestions", KnowledgeApi.json(Map.of("content", "x"))).status())
                .isEqualTo(404);
    }
}
