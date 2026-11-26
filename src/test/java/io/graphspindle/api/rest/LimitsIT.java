package io.graphspindle.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import io.graphspindle.support.KnowledgeApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** M5 acceptance: retrieval limits (ARCHITECTURE §8). */
class LimitsIT extends AbstractNeo4jIT {

    KnowledgeApi k;
    String gs;

    @BeforeEach
    void setUp() {
        k = new KnowledgeApi(api());
        k.brain("dev");
        k.brain("other");
        gs = k.entity("dev", "Project", "GraphSpindle");
    }

    @Test
    void searchLimitAbove50Is400() {
        var r = k.raw().get("/api/v1/brains/dev/search?q=graph&limit=51");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(k.raw().get("/api/v1/brains/dev/search?q=graph&limit=50").status()).isEqualTo(200);
        assertThat(k.raw().get("/api/v1/brains/dev/search?q=graph&limit=0").status()).isEqualTo(400);
        assertThat(k.raw().get("/api/v1/brains/dev/search?q=" + "x".repeat(501)).code()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(k.raw().get("/api/v1/brains/dev/search").status()).isEqualTo(400);
    }

    @Test
    void depthAbove2Is400() {
        var r = k.raw().get("/api/v1/brains/dev/entities/" + gs + "/neighbors?depth=3");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(k.raw().get("/api/v1/brains/dev/entities/" + gs + "/neighbors?depth=0").status()).isEqualTo(400);
        assertThat(k.raw().get("/api/v1/brains/dev/entities/" + gs + "/neighbors?depth=2").status()).isEqualTo(200);
        assertThat(k.raw().get("/api/v1/brains/dev/entities/" + gs + "/neighbors?limit=201").code())
                .isEqualTo("LIMIT_EXCEEDED");
        assertThat(k.raw().get("/api/v1/brains/dev/entities/" + gs + "/neighbors?predicates=INVENTED").status())
                .isEqualTo(400);
    }

    @Test
    void listLimitsAndCursorsAreValidated() {
        assertThat(k.raw().get("/api/v1/brains/dev/entities?limit=201").code()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(k.raw().get("/api/v1/brains/dev/claims?limit=201").code()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(k.raw().get("/api/v1/brains/dev/entities?cursor=DROP%20TABLE").status()).isEqualTo(400);
        assertThat(k.raw().get("/api/v1/brains/dev/entities?status=GONE").status()).isEqualTo(400);
        assertThat(k.raw().get("/api/v1/brains/dev/entities/" + gs + "?maxClaims=101").code())
                .isEqualTo("LIMIT_EXCEEDED");
    }
}
