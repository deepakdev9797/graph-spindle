package io.graphspindle.api.rest;

import static io.graphspindle.support.KnowledgeApi.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import io.graphspindle.support.ApiClient.Response;
import io.graphspindle.support.KnowledgeApi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Plain node views, point-in-time {@code asOf} reads and entity history (ADR-024, ADR-026). */
class EntityHistoryIT extends AbstractNeo4jIT {

    private KnowledgeApi k;
    private String saketh;
    private String acme;
    private String roleClaim;
    private String memberClaim;
    private Instant beforeCreation;
    private Instant whileBackend;
    private Instant afterSupersede;

    @BeforeEach
    void saketh() throws InterruptedException {
        k = new KnowledgeApi(api());
        k.brain("team");
        k.brain("other");
        beforeCreation = pause();
        saketh = k.entity("team", "Person", "Saketh");
        acme = k.entity("team", "Organization", "Acme");
        roleClaim = k.value("team", saketh, "role", "backend engineer");
        memberClaim = k.relation("team", saketh, "MEMBER_OF", acme);
        whileBackend = pause();
        Response r = k.raw().post("/api/v1/brains/team/claims/" + roleClaim + "/supersede", json(Map.of("reason",
                "moved to the data team", "replacement", Map.of("value", "data engineer"))));
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        afterSupersede = pause();
    }

    /** Waits so that record times on both sides of the returned instant differ. */
    private static Instant pause() throws InterruptedException {
        Thread.sleep(30);
        Instant now = Instant.now();
        Thread.sleep(30);
        return now;
    }

    private String entityPath(String id) {
        return "/api/v1/brains/team/entities/" + id;
    }

    private static List<String> relationships(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.get("relationships").forEach(r -> out.add(r.get("type").asString() + " " + r.get("direction").asString()
                + " " + r.get("entityName").asString()));
        return out;
    }

    @Test
    void plainViewShowsPropertiesAndRelationshipsAndHidesClaims() {
        JsonNode plain = k.get(entityPath(saketh));
        assertThat(plain.get("id").asString()).isEqualTo(saketh);
        assertThat(plain.get("type").asString()).isEqualTo("Person");
        assertThat(plain.get("name").asString()).isEqualTo("Saketh");
        assertThat(plain.get("properties").get("role").asString()).isEqualTo("data engineer");
        assertThat(relationships(plain)).containsExactly("MEMBER_OF OUT Acme");
        assertThat(plain.has("claims")).isFalse();
        assertThat(plain.has("entity")).isFalse();
        assertThat(plain.has("text")).isFalse();
        assertThat(plain.has("asOf")).isFalse();
        assertThat(plain.toString()).doesNotContain(roleClaim).doesNotContain("attr_");
        assertThat(relationships(k.get(entityPath(acme)))).containsExactly("MEMBER_OF IN Saketh");

        JsonNode withClaims = k.get(entityPath(saketh) + "?include=claims");
        assertThat(withClaims.get("properties").get("role").asString()).isEqualTo("data engineer");
        assertThat(withClaims.get("claims").toString()).contains("data engineer").contains(memberClaim);
        assertThat(withClaims.get("text").asString()).contains("Saketh");
        assertThat(k.raw().get(entityPath(saketh) + "?include=everything").status()).isEqualTo(400);
    }

    @Test
    void asOfReturnsWhatTheBrainBelievedThen() {
        JsonNode before = k.get(entityPath(saketh) + "?asOf=" + whileBackend);
        assertThat(before.get("properties").get("role").asString()).isEqualTo("backend engineer");
        assertThat(relationships(before)).containsExactly("MEMBER_OF OUT Acme");
        assertThat(Instant.parse(before.get("asOf").asString())).isEqualTo(whileBackend);
        assertThat(before.has("claims")).isFalse();

        JsonNode after = k.get(entityPath(saketh) + "?asOf=" + afterSupersede);
        assertThat(after.get("properties").get("role").asString()).isEqualTo("data engineer");

        // with claims: the claims believed at that time
        JsonNode beforeClaims = k.get(entityPath(saketh) + "?asOf=" + whileBackend + "&include=claims");
        assertThat(beforeClaims.get("claims").toString()).contains(roleClaim).doesNotContain("data engineer");

        // a date is the end of that day (UTC); before the entity existed is 404
        assertThat(k.get(entityPath(saketh) + "?asOf=2999-12-31").get("properties").get("role").asString())
                .isEqualTo("data engineer");
        assertThat(k.raw().get(entityPath(saketh) + "?asOf=" + beforeCreation).status()).isEqualTo(404);
        assertThat(k.raw().get(entityPath(saketh) + "?asOf=2000-01-01").status()).isEqualTo(404);
        Response bad = k.raw().get(entityPath(saketh) + "?asOf=last-tuesday");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.code()).isEqualTo("VALIDATION_FAILED");
        // a local date-time is UTC
        String local = whileBackend.toString().replace("Z", "");
        assertThat(k.get(entityPath(saketh) + "?asOf=" + local).get("properties").get("role").asString())
                .isEqualTo("backend engineer");
    }

    @Test
    void retractedRelationshipsDisappearFromLaterPointsInTime() throws InterruptedException {
        Response r = k.raw().post("/api/v1/brains/team/claims/" + memberClaim + "/retract",
                json(Map.of("reason", "left Acme")));
        assertThat(r.status()).isEqualTo(200);
        Instant afterRetract = pause();
        assertThat(relationships(k.get(entityPath(saketh)))).isEmpty();
        assertThat(relationships(k.get(entityPath(saketh) + "?asOf=" + afterSupersede)))
                .containsExactly("MEMBER_OF OUT Acme");
        assertThat(relationships(k.get(entityPath(saketh) + "?asOf=" + afterRetract))).isEmpty();

        JsonNode changes = k.get(entityPath(saketh) + "/history").get("changes");
        JsonNode last = changes.get(changes.size() - 1);
        assertThat(last.get("kind").asString()).isEqualTo("RELATIONSHIP");
        assertThat(last.get("change").asString()).isEqualTo("REMOVED");
        assertThat(last.get("reason").asString()).isEqualTo("left Acme");
        assertThat(last.get("claimStatus").asString()).isEqualTo("RETRACTED");
    }

    @Test
    void historyListsBothRolesInOrder() {
        // personal contact data is a normal property (ADR-024)
        k.value("team", saketh, "email", "saketh@example.com");
        JsonNode history = k.get(entityPath(saketh) + "/history");
        assertThat(history.get("entityId").asString()).isEqualTo(saketh);
        JsonNode changes = history.get("changes");
        List<String> summary = new ArrayList<>();
        changes.forEach(c -> summary.add(c.get("kind").asString() + " " + c.get("change").asString() + " "
                + c.get("name").asString()));
        assertThat(summary).containsExactly("PROPERTY ADDED role", "RELATIONSHIP ADDED MEMBER_OF",
                "PROPERTY CHANGED role", "PROPERTY ADDED email");

        JsonNode added = changes.get(0);
        assertThat(added.get("newValue").asString()).isEqualTo("backend engineer");
        assertThat(added.get("oldValue").isNull()).isTrue();
        assertThat(added.get("claimId").asString()).isEqualTo(roleClaim);
        assertThat(added.get("claimStatus").asString()).isEqualTo("SUPERSEDED");
        assertThat(added.get("origin").asString()).isEqualTo("MANUAL");

        JsonNode member = changes.get(1);
        assertThat(member.get("direction").asString()).isEqualTo("OUT");
        assertThat(member.get("entityId").asString()).isEqualTo(acme);
        assertThat(member.get("entityName").asString()).isEqualTo("Acme");

        JsonNode changed = changes.get(2);
        assertThat(changed.get("oldValue").asString()).isEqualTo("backend engineer");
        assertThat(changed.get("newValue").asString()).isEqualTo("data engineer");
        assertThat(changed.get("reason").asString()).isEqualTo("moved to the data team");
        assertThat(changed.get("claimStatus").asString()).isEqualTo("ACTIVE");
        assertThat(changed.get("principal").asString()).isEqualTo("admin");
        Instant at = Instant.parse(changed.get("at").asString());
        assertThat(at).isAfter(whileBackend).isBefore(afterSupersede);
        assertThat(Instant.parse(changes.get(0).get("at").asString())).isBefore(whileBackend);

        // the organization sees the incoming relationship only
        JsonNode acmeChanges = k.get(entityPath(acme) + "/history").get("changes");
        assertThat(acmeChanges.size()).isEqualTo(1);
        assertThat(acmeChanges.get(0).get("direction").asString()).isEqualTo("IN");
        assertThat(acmeChanges.get(0).get("entityName").asString()).isEqualTo("Saketh");

        // limit keeps the newest entries and marks the result truncated
        JsonNode limited = k.get(entityPath(saketh) + "/history?limit=1");
        assertThat(limited.get("changes").size()).isEqualTo(1);
        assertThat(limited.get("changes").get(0).get("name").asString()).isEqualTo("email");
        assertThat(limited.get("truncated").asBoolean()).isTrue();
        assertThat(k.raw().get(entityPath(saketh) + "/history?limit=1001").status()).isEqualTo(400);
    }

    @Test
    void anotherBrainCannotReadTheEntityOrItsHistory() {
        String base = "/api/v1/brains/other/entities/" + saketh;
        for (String path : List.of(base, base + "?asOf=" + afterSupersede, base + "/history",
                base + "?include=claims")) {
            Response r = k.raw().get(path);
            assertThat(r.status()).as(path).isEqualTo(404);
            assertThat(r.code()).isEqualTo("NOT_FOUND");
        }
        // the other brain's own entity of the same name has its own, empty history
        String otherSaketh = k.entity("other", "Person", "Saketh");
        assertThat(otherSaketh).isNotEqualTo(saketh);
        assertThat(k.get("/api/v1/brains/other/entities/" + otherSaketh + "/history").get("changes").size()).isZero();
        assertThat(k.get("/api/v1/brains/other/entities/" + otherSaketh).get("properties").size()).isZero();
        assertThat(k.raw().get("/api/v1/brains/nope/entities/" + saketh + "/history").status()).isEqualTo(404);
    }
}
