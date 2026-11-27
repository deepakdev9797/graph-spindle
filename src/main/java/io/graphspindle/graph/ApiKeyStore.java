package io.graphspindle.graph;

import io.graphspindle.common.BrainScope;
import io.graphspindle.graph.internal.ApiKeyStatements;
import io.graphspindle.graph.internal.GraphTransactions;
import io.graphspindle.graph.internal.P;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Component;

/** Persistence of API keys and their per-brain grants (ADR-033). Stores hashes only. */
@Component
public class ApiKeyStore {

    private final GraphTransactions tx;

    public ApiKeyStore(GraphTransactions tx) {
        this.tx = tx;
    }

    /** @param grants brain id to permission name */
    public ApiKeyRecord create(String keyId, String name, String secretHash, String createdBy, Instant expiresAt,
            Map<String, String> grants) {
        List<Map<String, Object>> g = grants.entrySet().stream()
                .map(e -> Map.<String, Object>of("brainId", e.getKey(), "permission", e.getValue())).toList();
        Map<String, Object> params = new HashMap<>();
        params.put("keyId", keyId);
        params.put("name", name);
        params.put("secretHash", secretHash);
        params.put("createdBy", createdBy);
        params.put("expiresAt", expiresAt == null ? null : expiresAt.atZone(ZoneOffset.UTC));
        params.put("grants", g);
        tx.writeUnscoped(t -> t.run(ApiKeyStatements.CREATE, params).consume());
        return find(keyId).orElseThrow();
    }

    public Optional<ApiKeyRecord> find(String keyId) {
        return tx.readUnscoped(t -> t.run(ApiKeyStatements.FIND, Map.of("keyId", keyId)).list().stream()
                .findFirst().map(ApiKeyStore::toRecord));
    }

    public List<ApiKeyRecord> listAll() {
        return tx.readUnscoped(t -> t.run(ApiKeyStatements.LIST_ALL).list(ApiKeyStore::toRecord));
    }

    public List<ApiKeyRecord> listForBrain(BrainScope scope) {
        return tx.read(scope, t -> t.run(ApiKeyStatements.LIST_FOR_BRAIN).list(ApiKeyStore::toRecord));
    }

    public Optional<ApiKeyRecord> findForBrain(BrainScope scope, String keyId) {
        return tx.read(scope, t -> t.run(ApiKeyStatements.FIND_FOR_BRAIN, P.of("keyId", keyId)).list().stream()
                .findFirst().map(ApiKeyStore::toRecord));
    }

    public Optional<ApiKeyRecord> revoke(String keyId) {
        return tx.writeUnscoped(t -> t.run(ApiKeyStatements.REVOKE, Map.of("keyId", keyId)).list().stream()
                .findFirst().map(ApiKeyStore::toRecord));
    }

    /** Removes the brain's grant (revoking a key left without grants); empty when there was none. */
    public Optional<ApiKeyRecord> removeGrant(BrainScope scope, String keyId) {
        return tx.write(scope, t -> t.run(ApiKeyStatements.REMOVE_GRANT, P.of("keyId", keyId)).list().stream()
                .findFirst().map(ApiKeyStore::toRecord));
    }

    public void touch(String keyId) {
        tx.writeUnscoped(t -> t.run(ApiKeyStatements.TOUCH, Map.of("keyId", keyId)).consume());
    }

    private static ApiKeyRecord toRecord(Record r) {
        Value k = r.get("k");
        Map<String, String> grants = new HashMap<>();
        r.get("grants").asList(v -> v).forEach(g -> grants.put(g.get("brainId").asString(),
                g.get("permission").asString()));
        return new ApiKeyRecord(k.get("id").asString(), k.get("name").asString(), k.get("secretHash").asString(),
                k.get("createdBy").asString(null), instant(k.get("createdAt")), instant(k.get("expiresAt")),
                instant(k.get("lastUsedAt")), instant(k.get("revokedAt")), grants);
    }

    private static Instant instant(Value v) {
        return v == null || v.isNull() ? null : v.asZonedDateTime().toInstant();
    }
}
