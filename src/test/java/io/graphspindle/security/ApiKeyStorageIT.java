package io.graphspindle.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import io.graphspindle.support.KnowledgeApi;
import io.graphspindle.support.TestKeys;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** IMPLEMENTATION_PLAN M9 {@code ApiKeyStorageIT}: only the hash is stored; the key is shown once. */
class ApiKeyStorageIT extends AbstractNeo4jIT {

    @Test
    void secretIsNeverPersistedAndShownOnlyOnCreation() {
        new KnowledgeApi(api()).brain("dev");
        var created = api().post("/api/v1/brains/dev/api-keys", "{\"name\":\"bot\",\"permission\":\"WRITE\"}");
        assertThat(created.status()).isEqualTo(201);
        String token = created.body().get("apiKey").asString();
        String id = created.body().get("key").get("id").asString();
        ApiKeyTokens.Parsed parsed = ApiKeyTokens.parse(token).orElseThrow();
        assertThat(created.body().get("notice").asString()).contains("only once");

        // every property of every node in the database: neither the token nor the secret appears
        List<Map<String, Object>> all = query("MATCH (n) RETURN properties(n) AS p", Map.of());
        String dump = all.toString();
        assertThat(dump).doesNotContain(parsed.secret()).doesNotContain(token).doesNotContain(TestKeys.ADMIN);
        Map<String, Object> key = query("MATCH (k:ApiKey {id: $id}) RETURN properties(k) AS p", Map.of("id", id))
                .get(0);
        assertThat(((Map<?, ?>) key.get("p")).get("secretHash")).isEqualTo(ApiKeyTokens.sha256Hex(parsed.secret()));
        assertThat(query("MATCH (g:ApiKeyGrant {keyId: $id}) RETURN g.brainId AS b, g.permission AS p",
                Map.of("id", id))).containsExactly(Map.of("b", "dev", "p", "WRITE"));

        // later reads never return the secret or its hash
        for (String path : List.of("/api/v1/api-keys", "/api/v1/brains/dev/api-keys",
                "/api/v1/brains/dev/api-keys/" + id)) {
            String body = api().get(path).body().toString();
            assertThat(body).contains(id).doesNotContain(parsed.secret()).doesNotContain("secretHash")
                    .doesNotContain(ApiKeyTokens.sha256Hex(parsed.secret()));
        }
        // and the key works, and records its last use
        assertThat(anonymous().withKey(token).get("/api/v1/brains/dev/entities").status()).isEqualTo(200);
        assertThat(api().get("/api/v1/brains/dev/api-keys/" + id).body().get("lastUsedAt").isNull()).isFalse();
        assertThat(api().post("/api/v1/brains/dev/api-keys", KnowledgeApi.json(Map.of("name", "x", "permission",
                "READ", "expiresAt", "2000-01-01T00:00:00Z"))).code()).isEqualTo("VALIDATION_FAILED");
    }
}
