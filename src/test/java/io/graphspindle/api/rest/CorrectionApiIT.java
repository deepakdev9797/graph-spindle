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

/** M5 acceptance: user corrections keep history (supersede) and remove projections (retract). */
class CorrectionApiIT extends AbstractNeo4jIT {

    KnowledgeApi k;
    String gs;
    String neo4j;
    String apache;
    String mit;
    String license;

    @BeforeEach
    void setUp() {
        k = new KnowledgeApi(api());
        k.brain("dev");
        k.brain("other");
        gs = k.entity("dev", "Project", "GraphSpindle");
        neo4j = k.entity("dev", "Technology", "Neo4j");
        apache = k.entity("dev", "Standard", "Apache License 2.0");
        mit = k.entity("dev", "Standard", "MIT License");
        license = k.relation("dev", gs, "LICENSED_UNDER", apache);
        String otherGs = k.entity("other", "Project", "GraphSpindle");
        k.relation("other", otherGs, "LICENSED_UNDER", k.entity("other", "Standard", "Apache License 2.0"));
    }

    @Test
    void supersedeKeepsTheHistoryChain() {
        var r = k.raw().post("/api/v1/brains/dev/claims/" + license + "/supersede", json(Map.of("reason",
                "license changed to MIT", "replacement", Map.of("objectId", mit))));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("outcome").asString()).isEqualTo("CREATED");
        assertThat(r.body().get("supersededClaimId").asString()).isEqualTo(license);
        String now = r.body().get("claim").get("id").asString();
        assertThat(r.body().get("claim").get("objectName").asString()).isEqualTo("MIT License");

        JsonNode old = k.get("/api/v1/brains/dev/claims/" + license);
        assertThat(old.get("claim").get("status").asString()).isEqualTo("SUPERSEDED");
        assertThat(old.get("claim").get("statusReason").asString()).isEqualTo("license changed to MIT");
        assertThat(texts(old.get("supersededBy"), null)).containsExactly(now);

        for (String id : new String[] {license, now}) {
            JsonNode history = k.get("/api/v1/brains/dev/claims/" + id + "/history");
            assertThat(texts(history, "claim.id")).containsExactly(license, now);
            assertThat(texts(history, "claim.status")).containsExactly("SUPERSEDED", "ACTIVE");
            assertThat(texts(history.get(1).get("supersedes"), null)).containsExactly(license);
            assertThat(history.get(1).get("supersedeReason").asString()).isEqualTo("license changed to MIT");
        }
        JsonNode n = k.get("/api/v1/brains/dev/entities/" + gs + "/neighbors?predicates=LICENSED_UNDER");
        assertThat(texts(n.get("nodes"), "entity.name")).containsExactly("MIT License");
        assertThat(k.get("/api/v1/brains/dev/claims?subjectId=" + gs + "&predicate=LICENSED_UNDER").get("items")
                .size()).isEqualTo(2);

        // a superseded claim cannot be corrected again; the other brain is untouched
        assertThat(k.raw().post("/api/v1/brains/dev/claims/" + license + "/supersede", json(Map.of("reason", "again",
                "replacement", Map.of("objectId", apache)))).code()).isEqualTo("CONFLICT_STATE");
        assertThat(k.get("/api/v1/brains/other/claims?status=ACTIVE").get("items").get(0).get("status").asString())
                .isEqualTo("ACTIVE");
        assertThat(k.get("/api/v1/brains/other/claims?status=SUPERSEDED").get("items").size()).isZero();
        assertThat(k.get("/api/v1/admin/brains/dev/projection-check").get("consistent").asBoolean()).isTrue();
    }

    @Test
    void retractRemovesTheProjection() {
        String uses = k.relation("dev", gs, "USES", neo4j);
        assertThat(texts(k.get("/api/v1/brains/dev/entities/" + gs + "/neighbors").get("nodes"), "entity.name"))
                .contains("Neo4j");
        var r = k.raw().post("/api/v1/brains/dev/claims/" + uses + "/retract", json(Map.of("reason", "wrong")));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("claim").get("status").asString()).isEqualTo("RETRACTED");
        assertThat(texts(k.get("/api/v1/brains/dev/entities/" + gs + "/neighbors").get("nodes"), "entity.name"))
                .doesNotContain("Neo4j");
        assertThat(count("MATCH ()-[e:USES {brainId: 'dev'}]->() RETURN count(e)", Map.of())).isZero();
        assertThat(k.raw().post("/api/v1/brains/dev/claims/" + uses + "/retract", json(Map.of("reason", "again")))
                .code()).isEqualTo("CONFLICT_STATE");
        assertThat(k.raw().post("/api/v1/brains/dev/claims/" + uses + "/retract", json(Map.of())).status())
                .isEqualTo(400);
        // the slot of an attribute is freed by retraction
        String status = k.value("dev", gs, "status", "ACTIVE");
        k.raw().post("/api/v1/brains/dev/claims/" + status + "/retract", json(Map.of("reason", "unknown")));
        assertThat(k.get("/api/v1/brains/dev/entities/" + gs).get("entity").get("attributes").has("status")).isFalse();
        assertThat(k.get("/api/v1/admin/brains/dev/projection-check").get("consistent").asBoolean()).isTrue();
    }

    @Test
    void contradictingManualAssertionIsDisputedNotOverwritten() {
        var r = k.assertRelation("dev", gs, "LICENSED_UNDER", mit);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("outcome").asString()).isEqualTo("DISPUTED");
        String conflictId = r.body().get("conflictId").asString();
        JsonNode conflict = k.get("/api/v1/brains/dev/conflicts/" + conflictId);
        assertThat(conflict.get("kind").asString()).isEqualTo("CONTRADICTION");
        assertThat(texts(conflict.get("claims"), "role")).containsExactly("EXISTING", "INCOMING");
        JsonNode context = k.get("/api/v1/brains/dev/entities/" + gs);
        assertThat(texts(context.get("disputed"), "claim.objectName")).containsExactly("MIT License");
        assertThat(texts(context.get("openConflictIds"), null)).containsExactly(conflictId);
        assertThat(k.get("/api/v1/brains/dev/claims/" + license).get("claim").get("status").asString())
                .isEqualTo("ACTIVE");
    }
}
