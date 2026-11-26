package io.graphspindle.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import io.graphspindle.support.ApiClient.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BrainApiIT extends AbstractNeo4jIT {

    static String brainJson(String id) {
        return "{\"id\":\"" + id + "\",\"name\":\"Brain " + id + "\",\"description\":\"test brain\"}";
    }

    @Test
    void createGetList() {
        Response created = api().post("/api/v1/brains", brainJson("alpha"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.headers().getLocation()).hasToString("/api/v1/brains/alpha");
        assertThat(created.etag()).isEqualTo("\"1\"");
        assertThat(created.body().get("id").asString()).isEqualTo("alpha");
        assertThat(created.body().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(created.body().get("configVersion").asLong()).isEqualTo(1);
        assertThat(created.body().get("config").get("conflictPolicy").asString())
                .isEqualTo("EXPLICIT_CORRECTIONS_SUPERSEDE");
        assertThat(created.body().get("config").get("retainRawContent").asBoolean()).isFalse();
        assertThat(created.body().get("config").get("minConfidence").asDouble()).isEqualTo(0.5);

        assertThat(api().post("/api/v1/brains", brainJson("beta")).status()).isEqualTo(201);

        Response got = api().get("/api/v1/brains/alpha");
        assertThat(got.status()).isEqualTo(200);
        assertThat(got.body().get("name").asString()).isEqualTo("Brain alpha");
        assertThat(got.body().get("selfEntityId").asString())
                .isEqualTo(created.body().get("selfEntityId").asString());

        Response list = api().get("/api/v1/brains");
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().valueStream().map(n -> n.get("id").asString())).containsExactly("alpha", "beta");
    }

    @Test
    void duplicateIdIsConflict() {
        assertThat(api().post("/api/v1/brains", brainJson("dup")).status()).isEqualTo(201);
        Response again = api().post("/api/v1/brains", brainJson("dup"));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("ALREADY_EXISTS");
        assertThat(again.headers().getContentType().toString()).startsWith("application/problem+json");
        assertThat(count("MATCH (b:Brain {id: 'dup'}) RETURN count(b)", Map.of())).isEqualTo(1);
        assertThat(count("MATCH (n:Entity {brainId: 'dup'}) RETURN count(n)", Map.of())).isEqualTo(1);
    }

    @Test
    void invalidRequestsAreRejected() {
        Response badId = api().post("/api/v1/brains", brainJson("Bad_Id"));
        assertThat(badId.status()).isEqualTo(400);
        assertThat(badId.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(api().post("/api/v1/brains", brainJson("admin")).status()).isEqualTo(400);
        assertThat(api().post("/api/v1/brains", "{\"id\":\"x1\"}").status()).isEqualTo(400);
        Response unknownField = api().post("/api/v1/brains", "{\"id\":\"x2\",\"name\":\"n\",\"brainId\":\"other\"}");
        assertThat(unknownField.status()).isEqualTo(400);
        assertThat(unknownField.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(api().post("/api/v1/brains", "not json").status()).isEqualTo(400);
        Response badConfig = api().post("/api/v1/brains",
                "{\"id\":\"x3\",\"name\":\"n\",\"config\":{\"minConfidence\":7}}");
        assertThat(badConfig.status()).isEqualTo(400);
        assertThat(count("MATCH (b:Brain) RETURN count(b)", Map.of())).isZero();
    }

    @Test
    void unknownBrainIsNotFound() {
        Response r = api().get("/api/v1/brains/nope");
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("BRAIN_NOT_FOUND");
        assertThat(api().get("/api/v1/brains/INVALID_ID").code()).isEqualTo("BRAIN_NOT_FOUND");
    }

    @Test
    void selfPersonEntityIsCreated() {
        Response created = api().post("/api/v1/brains",
                "{\"id\":\"selfy\",\"name\":\"Selfy\",\"ownerName\":\"Deepak\"}");
        String selfId = created.body().get("selfEntityId").asString();
        var rows = query("MATCH (s:Entity:Person {brainId: 'selfy', canonicalKey: 'person:@self'}) RETURN s.id AS id,"
                + " s.name AS name, s.origin AS origin, s.type AS type, s.status AS status", Map.of());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("id", selfId).containsEntry("name", "Deepak")
                .containsEntry("origin", "SYSTEM").containsEntry("type", "Person").containsEntry("status", "ACTIVE");
    }

    @Test
    void deleteRequiresConfirmAndOnlyRemovesThatBrain() {
        api().post("/api/v1/brains", brainJson("doomed"));
        api().post("/api/v1/brains", brainJson("keeper"));
        // extra knowledge-like nodes and edges in both brains, including identical names
        for (String b : new String[] {"doomed", "keeper"}) {
            write("MATCH (s:Entity {brainId: $b, canonicalKey: 'person:@self'})"
                    + " CREATE (e:Entity:Project {brainId: $b, id: $b + '-p', canonicalKey: 'project:x', name: 'X'})"
                    + " CREATE (c:Claim {brainId: $b, id: $b + '-c'})-[:SUBJECT {brainId: $b}]->(s)"
                    + " CREATE (c)-[:OBJECT {brainId: $b}]->(e)"
                    + " CREATE (s)-[:WORKS_ON {brainId: $b, claimId: $b + '-c'}]->(e)", Map.of("b", b));
        }
        long keeperNodes = count("MATCH (n {brainId: 'keeper'}) RETURN count(n)", Map.of());
        long keeperRels = count("MATCH ()-[r {brainId: 'keeper'}]->() RETURN count(r)", Map.of());

        Response missing = api().delete("/api/v1/brains/doomed");
        assertThat(missing.status()).isEqualTo(400);
        assertThat(api().delete("/api/v1/brains/doomed?confirm=keeper").status()).isEqualTo(400);
        assertThat(count("MATCH (n {brainId: 'doomed'}) RETURN count(n)", Map.of())).isEqualTo(4);

        assertThat(api().delete("/api/v1/brains/doomed?confirm=doomed").status()).isEqualTo(204);
        assertThat(count("MATCH (n {brainId: 'doomed'}) RETURN count(n)", Map.of())).isZero();
        assertThat(count("MATCH ()-[r {brainId: 'doomed'}]->() RETURN count(r)", Map.of())).isZero();
        assertThat(api().get("/api/v1/brains/doomed").status()).isEqualTo(404);

        assertThat(count("MATCH (n {brainId: 'keeper'}) RETURN count(n)", Map.of())).isEqualTo(keeperNodes);
        assertThat(count("MATCH ()-[r {brainId: 'keeper'}]->() RETURN count(r)", Map.of())).isEqualTo(keeperRels);
        assertThat(api().get("/api/v1/brains/keeper").status()).isEqualTo(200);
        assertThat(api().delete("/api/v1/brains/ghost?confirm=ghost").status()).isEqualTo(404);
    }
}
