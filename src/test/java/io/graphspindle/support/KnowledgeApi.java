package io.graphspindle.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.ApiClient.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small helpers over the M5 REST API for ITs (each call asserts the expected success status). */
public final class KnowledgeApi {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ApiClient api;

    public KnowledgeApi(ApiClient api) {
        this.api = api;
    }

    public static String json(Object o) {
        return JSON.writeValueAsString(o);
    }

    public ApiClient raw() {
        return api;
    }

    public void brain(String id) {
        assertThat(api.post("/api/v1/brains", json(Map.of("id", id, "name", "Brain " + id))).status()).isEqualTo(201);
    }

    public String selfId(String brain) {
        return api.get("/api/v1/brains/" + brain).body().get("selfEntityId").asString();
    }

    public String entity(String brain, String type, String name, String... aliases) {
        return described(brain, type, name, null, aliases);
    }

    public String described(String brain, String type, String name, String description, String... aliases) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("type", type, "name", name, "aliases",
                List.of(aliases)));
        if (description != null) {
            body.put("description", description);
        }
        Response r = api.post("/api/v1/brains/" + brain + "/entities", json(body));
        assertThat(r.status()).as(r.body() == null ? "" : r.body().toString()).isIn(200, 201);
        return r.body().get("entity").get("id").asString();
    }

    public Response assertRelation(String brain, String subjectId, String predicate, String objectId) {
        return api.post("/api/v1/brains/" + brain + "/claims", json(Map.of("subjectId", subjectId, "predicate",
                predicate, "objectId", objectId)));
    }

    public Response assertValue(String brain, String subjectId, String predicate, String value) {
        return api.post("/api/v1/brains/" + brain + "/claims", json(Map.of("subjectId", subjectId, "predicate",
                predicate, "value", value)));
    }

    /** Asserts and returns the claim id (expects CREATED). */
    public String relation(String brain, String subjectId, String predicate, String objectId) {
        Response r = assertRelation(brain, subjectId, predicate, objectId);
        assertThat(r.status()).as(r.body() == null ? "" : r.body().toString()).isEqualTo(201);
        assertThat(r.body().get("outcome").asString()).isEqualTo("CREATED");
        return r.body().get("claim").get("id").asString();
    }

    public String value(String brain, String subjectId, String predicate, String value) {
        Response r = assertValue(brain, subjectId, predicate, value);
        assertThat(r.status()).as(r.body() == null ? "" : r.body().toString()).isEqualTo(201);
        return r.body().get("claim").get("id").asString();
    }

    public JsonNode get(String path) {
        Response r = api.get(path);
        assertThat(r.status()).as(path + " " + (r.body() == null ? "" : r.body().toString())).isEqualTo(200);
        return r.body();
    }

    public static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(field == null ? n.asString() : pick(n, field)));
        return out;
    }

    private static String pick(JsonNode n, String path) {
        JsonNode cur = n;
        for (String p : path.split("\\.")) {
            cur = cur == null ? null : cur.get(p);
        }
        return cur == null || cur.isNull() ? null : cur.asString();
    }
}
