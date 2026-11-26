package io.graphspindle.support;

import io.graphspindle.llm.FakeChatModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Base for LLM ingestion ITs: the shared Neo4j harness plus the scripted {@link FakeChatModel} (no
 * network). Every test starts with two brains, {@code dev} and {@code other}; {@code other} holds
 * a sentinel entity so tests can assert that nothing of it leaks into prompts or changes.
 */
@Import(FakeLlmTestConfig.class)
public abstract class AbstractIngestionIT extends AbstractNeo4jIT {

    public static final String SENTINEL = "Zebrafish Quorum";
    protected static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    protected FakeChatModel fake;

    protected KnowledgeApi k;

    @BeforeEach
    void brains() {
        fake.reset();
        k = new KnowledgeApi(api());
        k.brain("dev");
        k.brain("other");
        k.entity("other", "Project", "GraphSpindle");
        k.entity("other", "Project", SENTINEL);
    }

    /** POST /ingestions with the fixture's content, sources and speaker. */
    protected ApiClient.Response ingest(String brain, String scenario, String requestId) {
        JsonNode d = fixture(scenario);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", d.get("content").asString());
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        if (d.has("sources")) {
            body.put("sources", JSON.convertValue(d.get("sources"), List.class));
        }
        if (d.has("speaker")) {
            body.put("speaker", JSON.convertValue(d.get("speaker"), Map.class));
        }
        return ingest(brain, body, "");
    }

    protected ApiClient.Response ingest(String brain, Map<String, Object> body, String query) {
        return api().post("/api/v1/brains/" + brain + "/ingestions" + query, KnowledgeApi.json(body));
    }

    protected static JsonNode fixture(String scenario) {
        return JSON.readTree(FakeChatModel.resource("fake-llm/" + scenario + ".json"));
    }

    /**
     * Registers the fixture for an end-to-end run: candidate handles of the fixture ({@code k1},
     * {@code c1}) in the scripted answers become {@code @entity:<name>} / {@code @claim:<subject>|<predicate>}
     * references that the fake resolves against the handles the engine really showed.
     */
    protected void endToEnd(String scenario) {
        ObjectNode d = (ObjectNode) fixture(scenario);
        Map<String, String> entities = new LinkedHashMap<>();
        d.path("candidates").path("entities").forEach(e -> entities.put(e.get("handle").asString(),
                e.get("name").asString()));
        Map<String, String> claims = new LinkedHashMap<>();
        d.path("candidates").path("claims").forEach(c -> {
            String subject = c.get("subject").asString();
            claims.put(c.get("handle").asString(), (subject.equals("P0") ? "P0" : entities.get(subject)) + "|"
                    + c.get("predicate").asString());
        });
        ObjectNode responses = (ObjectNode) d.get("responses");
        for (String stage : new ArrayList<>(responses.propertyNames())) {
            responses.set(stage, rewrite(responses.get(stage), entities, claims, null));
        }
        fake.load(d);
    }

    private static JsonNode rewrite(JsonNode n, Map<String, String> entities, Map<String, String> claims,
            String field) {
        if (n.isString()) {
            String v = n.asString();
            if ("id".equals(field) && entities.containsKey(v)) {
                return JSON.getNodeFactory().stringNode("@entity:" + entities.get(v));
            }
            if ("claimId".equals(field) && claims.containsKey(v)) {
                return JSON.getNodeFactory().stringNode("@claim:" + claims.get(v));
            }
            return n;
        }
        if (n.isArray()) {
            var a = JSON.createArrayNode();
            n.forEach(e -> a.add(rewrite(e, entities, claims, null)));
            return a;
        }
        if (n.isObject()) {
            var o = JSON.createObjectNode();
            n.propertyNames().forEach(key -> o.set(key, rewrite(n.get(key), entities, claims, key)));
            return o;
        }
        return n;
    }

    /**
     * Scripts one end-to-end ingestion of {@code content}: an extraction with the given mentions
     * ({@code "Name:Type"} each) and a reconcile answer with the given operations (a JSON array body;
     * {@code @entity:}/{@code @claim:} references are resolved by the fake).
     */
    protected void script(String content, List<String> mentions, String operations) {
        StringBuilder m = new StringBuilder();
        for (int i = 0; i < mentions.size(); i++) {
            String[] nt = mentions.get(i).split(":", 2);
            if (i > 0) {
                m.append(',');
            }
            m.append("{\"localRef\":\"m").append(i + 1).append("\",\"name\":").append(JSON.writeValueAsString(nt[0]))
                    .append(",\"type\":").append(JSON.writeValueAsString(nt[1])).append(",\"evidence\":")
                    .append(JSON.writeValueAsString(nt[0])).append('}');
        }
        String scenario = "s-" + io.graphspindle.common.Hashing.sha256Hex(content).substring(0, 12);
        fake.script(scenario, content, "extract-v1", "{\"json\":{\"summary\":\"\",\"mentions\":[" + m
                + "],\"statements\":[]}}");
        fake.script(scenario, content, "reconcile-v1", "{\"json\":{\"summary\":\"\",\"operations\":" + operations
                + "}}");
    }

    /** Ingests plain content (201 expected) and returns the result body. */
    protected JsonNode ingestText(String brain, String content, String query) {
        ApiClient.Response r = ingest(brain, Map.of("content", content), query);
        org.assertj.core.api.Assertions.assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body();
    }

    /** The ids of the OPEN conflicts of a brain with this kind. */
    protected List<String> openConflicts(String brain, String kind) {
        return query("MATCH (x:Conflict {brainId: $b, status: 'OPEN', kind: $k}) RETURN x.id AS id ORDER BY id",
                Map.of("b", brain, "k", kind)).stream().map(r -> (String) r.get("id")).toList();
    }

    /** Status of the claims of a brain for a subject name and predicate, oldest first, as object=status. */
    protected List<String> claimStates(String brain, String subject, String predicate) {
        return query("""
                MATCH (s:Entity {brainId: $b, name: $s})<-[:SUBJECT]-(c:Claim {brainId: $b, predicate: $p})
                OPTIONAL MATCH (c)-[:OBJECT]->(o:Entity)
                RETURN coalesce(o.name, c.value) AS o, c.polarity AS pol, c.status AS st ORDER BY c.recordedAt""",
                Map.of("b", brain, "s", subject, "p", predicate)).stream()
                .map(r -> r.get("o") + (r.get("pol").equals("NEGATIVE") ? "(neg)" : "") + "=" + r.get("st")).toList();
    }

    /** Names reached over the projection edge {@code predicate} from the named entity. */
    protected List<String> projected(String brain, String subject, String predicate) {
        return query("MATCH (:Entity {brainId: $b, name: $s})-[r]->(o:Entity) WHERE type(r) = $p RETURN o.name AS o"
                + " ORDER BY o", Map.of("b", brain, "s", subject, "p", predicate)).stream()
                .map(r -> (String) r.get("o")).toList();
    }

    /** Resolves a conflict over REST. */
    protected ApiClient.Response resolve(String brain, String conflictId, String resolution) {
        return api().post("/api/v1/brains/" + brain + "/conflicts/" + conflictId + "/resolve",
                KnowledgeApi.json(Map.of("resolution", resolution)));
    }

    /** Counts nodes of a brain by label. */
    protected long nodes(String brain, String label) {
        return count("MATCH (n:" + label + " {brainId: $b}) RETURN count(n)", Map.of("b", brain));
    }

    protected long claims(String brain, String status) {
        return count("MATCH (c:Claim {brainId: $b, status: $s}) RETURN count(c)", Map.of("b", brain, "s", status));
    }

    /** The other brain is exactly as {@link #brains()} left it. */
    protected void assertOtherBrainUntouched() {
        org.assertj.core.api.Assertions.assertThat(nodes("other", "Entity")).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(nodes("other", "Claim")).isZero();
        org.assertj.core.api.Assertions.assertThat(nodes("other", "Ingestion")).isZero();
        org.assertj.core.api.Assertions.assertThat(count("MATCH (n {brainId: 'other'})-[r]-(m) WHERE m.brainId <> 'other'"
                + " RETURN count(r)", Map.of())).isZero();
        for (FakeChatModel.Call c : fake.calls()) {
            org.assertj.core.api.Assertions.assertThat(c.text()).doesNotContain(SENTINEL);
        }
    }
}
