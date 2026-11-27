package io.graphspindle.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.graphspindle.support.AbstractMcpIT;
import io.graphspindle.support.ApiClient;
import io.graphspindle.support.KnowledgeApi;
import io.graphspindle.support.TestKeys;
import io.modelcontextprotocol.client.McpSyncClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/**
 * IMPLEMENTATION_PLAN M9 {@code AuthIT} (ADR-033): a matrix over every REST endpoint and every MCP
 * tool. No key, a wrong key, a revoked or an expired key: 401 (MCP: the client cannot even connect).
 * A key for brain {@code other} used on {@code dev}: 404 {@code BRAIN_NOT_FOUND}, exactly like a
 * missing brain. A READ key cannot write (403), a WRITE key cannot administer (403), a brain ADMIN key
 * manages its brain's keys but not brains or other keys. Allowed calls use empty or unknown ids, so
 * they fail only after authorization (400/404 NOT_FOUND) and change nothing.
 */
class AuthIT extends AbstractMcpIT {

    static final String NO_ID = "00000000-0000-0000-0000-000000000000";
    static final String NO_KEY = "fedcba9876543210";
    static final Set<String> AUTH_CODES = Set.of("UNAUTHENTICATED", "FORBIDDEN", "BRAIN_NOT_FOUND");

    enum Need { READ, WRITE, ADMIN, GLOBAL, ANY }

    record Endpoint(HttpMethod method, String path, String body, Need need) {

        String on(String brain) {
            return path.replace("{b}", brain);
        }

        boolean brainScoped() {
            return path.contains("{b}");
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    static Endpoint e(String method, String path, String body, Need need) {
        return new Endpoint(HttpMethod.valueOf(method), path, body, need);
    }

    static final String B = "/api/v1/brains/{b}";

    /** Every REST endpoint (ApiKeyController, BrainController, ... ): kept complete by {@link #matrixCoversEveryMapping}. */
    static final List<Endpoint> REST = List.of(
            e("GET", "/api/v1/node-types", null, Need.ANY),
            e("GET", "/api/v1/node-types/Person", null, Need.ANY),
            e("GET", "/api/v1/relationship-types", null, Need.ANY),
            e("GET", "/api/v1/relationship-types/USES", null, Need.ANY),
            e("POST", "/api/v1/brains", "{}", Need.GLOBAL),
            e("GET", "/api/v1/brains", null, Need.ANY),
            e("GET", B, null, Need.READ),
            e("PATCH", B, "{}", Need.ADMIN),
            e("DELETE", B + "?confirm=nope", null, Need.ADMIN),
            e("GET", B + "/vocabulary", null, Need.READ),
            e("PUT", B + "/vocabulary", "{}", Need.ADMIN),
            e("GET", B + "/node-types", null, Need.READ),
            e("GET", B + "/node-types/Person", null, Need.READ),
            e("POST", B + "/node-types", "{}", Need.ADMIN),
            e("PUT", B + "/node-types/Nope", "{}", Need.ADMIN),
            e("DELETE", B + "/node-types/Nope", null, Need.ADMIN),
            e("GET", B + "/relationship-types", null, Need.READ),
            e("GET", B + "/relationship-types/USES", null, Need.READ),
            e("POST", B + "/relationship-types", "{}", Need.ADMIN),
            e("PUT", B + "/relationship-types/NOPE", "{}", Need.ADMIN),
            e("DELETE", B + "/relationship-types/NOPE", null, Need.ADMIN),
            e("POST", B + "/ingestions", "{}", Need.WRITE),
            e("GET", B + "/ingestions/" + NO_ID, null, Need.READ),
            e("GET", B + "/ingestions?requestId=nope", null, Need.READ),
            e("GET", B + "/entities", null, Need.READ),
            e("POST", B + "/entities", "{}", Need.WRITE),
            e("PATCH", B + "/entities/" + NO_ID, "{}", Need.WRITE),
            e("DELETE", B + "/entities/" + NO_ID, null, Need.WRITE),
            e("GET", B + "/entities/" + NO_ID + "/aliases", null, Need.READ),
            e("POST", B + "/entities/" + NO_ID + "/aliases", "{}", Need.WRITE),
            e("POST", B + "/entities/merge", "{}", Need.WRITE),
            e("GET", B + "/claims", null, Need.READ),
            e("POST", B + "/claims", "{}", Need.WRITE),
            e("GET", B + "/claims/" + NO_ID, null, Need.READ),
            e("GET", B + "/claims/" + NO_ID + "/history", null, Need.READ),
            e("POST", B + "/claims/" + NO_ID + "/supersede", "{}", Need.WRITE),
            e("POST", B + "/claims/" + NO_ID + "/retract", "{}", Need.WRITE),
            e("GET", B + "/sources", null, Need.READ),
            e("GET", B + "/sources/" + NO_ID, null, Need.READ),
            e("GET", B + "/search?q=x", null, Need.READ),
            e("GET", B + "/entities/" + NO_ID, null, Need.READ),
            e("GET", B + "/entities/" + NO_ID + "/history", null, Need.READ),
            e("GET", B + "/entities/" + NO_ID + "/neighbors", null, Need.READ),
            e("GET", B + "/conflicts", null, Need.READ),
            e("GET", B + "/conflicts/" + NO_ID, null, Need.READ),
            e("POST", B + "/conflicts/" + NO_ID + "/resolve", "{}", Need.WRITE),
            e("GET", "/api/v1/admin/brains/{b}/projection-check", null, Need.ADMIN),
            e("POST", "/api/v1/api-keys", "{}", Need.GLOBAL),
            e("GET", "/api/v1/api-keys", null, Need.GLOBAL),
            e("DELETE", "/api/v1/api-keys/" + NO_KEY, null, Need.GLOBAL),
            e("POST", B + "/api-keys", "{}", Need.ADMIN),
            e("GET", B + "/api-keys", null, Need.ADMIN),
            e("GET", B + "/api-keys/" + NO_KEY, null, Need.ADMIN),
            e("DELETE", B + "/api-keys/" + NO_KEY, null, Need.ADMIN));

    record Tool(String name, Need need, Object... args) {
    }

    /** Every MCP tool with arguments that fail only after authorization. */
    static final List<Tool> TOOLS = List.of(
            new Tool("get_brain", Need.READ),
            new Tool("list_node_types", Need.READ),
            new Tool("list_relationship_types", Need.READ),
            new Tool("remember", Need.WRITE, "content", ""),
            new Tool("get_ingestion", Need.READ, "ingestionId", NO_ID),
            new Tool("search_knowledge", Need.READ, "query", "x"),
            new Tool("list_entities", Need.READ),
            new Tool("get_entity", Need.READ, "entityId", NO_ID),
            new Tool("get_entity_history", Need.READ, "entityId", NO_ID),
            new Tool("get_neighbors", Need.READ, "entityId", NO_ID),
            new Tool("list_claims", Need.READ),
            new Tool("get_claim", Need.READ, "claimId", NO_ID),
            new Tool("get_claim_history", Need.READ, "claimId", NO_ID),
            new Tool("assert_claim", Need.WRITE, "subjectId", NO_ID, "predicate", "USES", "objectId", NO_ID),
            new Tool("supersede_claim", Need.WRITE, "claimId", NO_ID, "reason", "x", "objectId", NO_ID),
            new Tool("retract_claim", Need.WRITE, "claimId", NO_ID, "reason", "x"),
            new Tool("list_conflicts", Need.READ),
            new Tool("get_conflict", Need.READ, "conflictId", NO_ID),
            new Tool("resolve_conflict", Need.WRITE, "conflictId", NO_ID, "resolution", "ACCEPT_NEW"));

    private String key(String brain, String permission) {
        var r = api().post("/api/v1/brains/" + brain + "/api-keys",
                KnowledgeApi.json(Map.of("name", permission + " key", "permission", permission)));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.body().get("apiKey").asString();
    }

    private static String keyId(String token) {
        return token.substring(4, 20);
    }

    @Test
    void matrixCoversEveryMapping() {
        assertThat(REST).hasSize(54);
        assertThat(TOOLS).hasSize(19); // + list_brains, checked separately
        assertThat(mcp.listTools().tools()).hasSize(TOOLS.size() + 1);
    }

    @Test
    void missingWrongRevokedAndExpiredKeysAre401() {
        String revoked = key("dev", "ADMIN");
        assertThat(api().delete("/api/v1/api-keys/" + keyId(revoked)).body().get("status").asString())
                .isEqualTo("REVOKED");
        String expired = key("dev", "ADMIN");
        write("MATCH (k:ApiKey {id: $id}) SET k.expiresAt = datetime() - duration('PT1S')",
                Map.of("id", keyId(expired)));
        String valid = key("dev", "ADMIN");
        String wrongSecret = valid.substring(0, valid.length() - 4) + (valid.endsWith("AAAA") ? "BBBB" : "AAAA");
        List<String> bad = new ArrayList<>(List.of("nope", "gsk_" + NO_KEY + "_" + "a".repeat(43), wrongSecret,
                revoked, expired, TestKeys.ADMIN + "x"));
        bad.add(null);
        for (String token : bad) {
            ApiClient client = anonymous().withKey(token);
            for (Endpoint ep : REST) {
                var r = client.exchange(ep.method(), ep.on("dev"), ep.body(), null);
                assertThat(r.status()).as("%s with %s", ep, token == null ? "no key" : "bad key").isEqualTo(401);
                assertThat(r.code()).isEqualTo("UNAUTHENTICATED");
                assertThat(r.headers().getFirst("WWW-Authenticate")).startsWith("Bearer");
                if (token != null && r.body() != null) {
                    assertThat(r.body().toString()).doesNotContain(token);
                }
            }
            try (McpSyncClient c = client(token)) {
                assertThatThrownBy(c::initialize).as("MCP with %s", token).isInstanceOf(RuntimeException.class);
            }
        }
        // Bearer works like X-API-Key; a conflicting pair is refused
        var raw = org.springframework.web.client.RestClient.builder().baseUrl(baseUrl())
                .defaultStatusHandler(s -> true, (req, res) -> { }).build();
        assertThat(raw.get().uri("/api/v1/brains").header("Authorization", "Bearer " + valid).retrieve()
                .toBodilessEntity().getStatusCode().value()).isEqualTo(200);
        assertThat(raw.get().uri("/api/v1/brains").header("Authorization", "Bearer " + valid)
                .header("X-API-Key", TestKeys.ADMIN).retrieve().toBodilessEntity().getStatusCode().value())
                .isEqualTo(401);
        assertThat(raw.get().uri("/api/v1/brains").header("Authorization", "Basic Zm9vOmJhcg==").retrieve()
                .toBodilessEntity().getStatusCode().value()).isEqualTo(401);
        // the health endpoint stays open, without details
        var health = anonymous().get("/actuator/health");
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.body().has("status")).isTrue();
        assertThat(health.body().has("components") || health.body().has("details")).isFalse();
        assertThat(anonymous().get("/actuator/info").status()).isEqualTo(401);
    }

    @Test
    void keyOfAnotherBrainSeesNoBrainAtAll() {
        String foreignKey = key("other", "ADMIN");
        ApiClient foreign = anonymous().withKey(foreignKey);
        for (Endpoint ep : REST) {
            var r = foreign.exchange(ep.method(), ep.on("dev"), ep.body(), null);
            if (ep.brainScoped()) {
                assertThat(r.status()).as(ep.toString()).isEqualTo(404);
                assertThat(r.code()).as(ep.toString()).isEqualTo("BRAIN_NOT_FOUND");
                // identical to a brain that does not exist
                var missing = foreign.exchange(ep.method(), ep.on("no-such-brain"), ep.body(), null);
                assertThat(missing.code()).isEqualTo("BRAIN_NOT_FOUND");
                assertThat(r.body().get("detail").asString())
                        .isEqualTo(missing.body().get("detail").asString().replace("no-such-brain", "dev"));
            }
        }
        assertThat(KnowledgeApi.texts(foreign.get("/api/v1/brains").body(), "id")).containsExactly("other");
        try (McpSyncClient c = client(foreignKey)) {
            c.initialize();
            for (Tool t : TOOLS) {
                ToolResult r = call(c, t.name(), args("dev", t.args()));
                assertThat(r.error()).as(t.name()).isTrue();
                assertThat(r.code()).as(t.name()).isEqualTo("BRAIN_NOT_FOUND");
            }
            JsonNode brains = call(c, "list_brains").result();
            assertThat(KnowledgeApi.texts(brains, "id")).containsExactly("other");
        }
    }

    @Test
    void permissionsAreEnforcedPerLevelOverRestAndMcp() {
        for (String level : List.of("READ", "WRITE", "ADMIN")) {
            String token = key("dev", level);
            ApiClient client = anonymous().withKey(token);
            for (Endpoint ep : REST) {
                var r = client.exchange(ep.method(), ep.on("dev"), ep.body(), null);
                boolean allowed = switch (ep.need()) {
                    case ANY -> true;
                    case READ -> true;
                    case WRITE -> !level.equals("READ");
                    case ADMIN -> level.equals("ADMIN");
                    case GLOBAL -> false;
                };
                if (allowed) {
                    assertThat(r.code()).as("%s with %s: %s", ep, level, r.body()).isNotIn(AUTH_CODES);
                } else {
                    assertThat(r.status()).as("%s with %s: %s", ep, level, r.body()).isEqualTo(403);
                    assertThat(r.code()).isEqualTo("FORBIDDEN");
                }
            }
            try (McpSyncClient c = client(token)) {
                c.initialize();
                for (Tool t : TOOLS) {
                    ToolResult r = call(c, t.name(), args("dev", t.args()));
                    if (t.need() == Need.WRITE && level.equals("READ")) {
                        assertThat(r.code()).as(t.name()).isEqualTo("FORBIDDEN");
                        assertErrorParity(r, client.post("/api/v1/brains/dev/claims/" + NO_ID + "/retract",
                                "{\"reason\":\"x\"}"));
                    } else {
                        assertThat(r.code()).as("%s with %s: %s", t.name(), level, r.body()).isNotIn(AUTH_CODES);
                    }
                }
                assertThat(KnowledgeApi.texts(call(c, "list_brains").result(), "id")).containsExactly("dev");
            }
        }
        // nothing was changed by any of the calls
        assertThat(count("MATCH (n:Entity {brainId: 'dev'}) RETURN count(n)", Map.of())).isEqualTo(1);
        assertThat(count("MATCH (n:Claim) RETURN count(n)", Map.of())).isZero();
    }

    @Test
    void brainAdminManagesOnlyItsBrainsKeys() {
        ApiClient devAdmin = anonymous().withKey(key("dev", "ADMIN"));
        var created = devAdmin.post("/api/v1/brains/dev/api-keys", "{\"name\":\"ci\",\"permission\":\"READ\"}");
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.headers().getCacheControl()).contains("no-store");
        String token = created.body().get("apiKey").asString();
        assertThat(created.body().get("key").get("grants").toString()).contains("\"dev\"", "READ");
        String id = created.body().get("key").get("id").asString();

        assertThat(anonymous().withKey(token).get("/api/v1/brains/dev").status()).isEqualTo(200);
        assertThat(KnowledgeApi.texts(devAdmin.get("/api/v1/brains/dev/api-keys").body(), "id")).contains(id);
        // the listing never shows a secret or a hash
        String listing = api().get("/api/v1/api-keys").body().toString();
        assertThat(listing).contains(id).doesNotContain(token.substring(21)).doesNotContain("secretHash");

        assertThat(devAdmin.post("/api/v1/brains/other/api-keys", "{\"name\":\"x\",\"permission\":\"READ\"}")
                .code()).isEqualTo("BRAIN_NOT_FOUND");
        assertThat(devAdmin.post("/api/v1/brains", "{\"id\":\"x1\",\"name\":\"x\"}").code()).isEqualTo("FORBIDDEN");

        // revocation through the brain removes the only grant: the key stops working at once
        var revoked = devAdmin.delete("/api/v1/brains/dev/api-keys/" + id);
        assertThat(revoked.body().get("status").asString()).isEqualTo("REVOKED");
        assertThat(anonymous().withKey(token).get("/api/v1/brains/dev").status()).isEqualTo(401);
    }

    @Test
    void multiBrainKeyAndBrainDeletion() {
        var r = api().post("/api/v1/api-keys", KnowledgeApi.json(Map.of("name", "agent", "expiresAt",
                Instant.now().plusSeconds(3600).toString(), "grants", List.of(Map.of("brainId", "dev", "permission",
                        "WRITE"), Map.of("brainId", "other", "permission", "READ")))));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        ApiClient agent = anonymous().withKey(r.body().get("apiKey").asString());
        String id = r.body().get("key").get("id").asString();
        assertThat(KnowledgeApi.texts(agent.get("/api/v1/brains").body(), "id")).containsExactly("dev", "other");
        assertThat(agent.post("/api/v1/brains/other/entities", "{}").code()).isEqualTo("FORBIDDEN");

        // a brain admin of dev removes only dev's grant
        api().delete("/api/v1/brains/dev/api-keys/" + id);
        assertThat(agent.get("/api/v1/brains/dev").code()).isEqualTo("BRAIN_NOT_FOUND");
        assertThat(agent.get("/api/v1/brains/other").status()).isEqualTo(200);

        // deleting a brain deletes its grants: a re-created brain with the same id grants nothing
        assertThat(api().delete("/api/v1/brains/other?confirm=other").status()).isEqualTo(204);
        k.brain("other");
        assertThat(agent.get("/api/v1/brains/other").status()).isEqualTo(401);
        assertThat(count("MATCH (g:ApiKeyGrant {keyId: $id}) RETURN count(g)", Map.of("id", id))).isZero();
    }

    private static Object[] args(String brain, Object... rest) {
        Object[] all = new Object[rest.length + 2];
        all[0] = "brainId";
        all[1] = brain;
        System.arraycopy(rest, 0, all, 2, rest.length);
        return all;
    }
}
