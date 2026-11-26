package io.graphspindle.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractIngestionIT;
import io.graphspindle.support.ApiClient.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** QA M6 review: failing tests for confirmed findings. */
class QaM6SecurityIT extends AbstractIngestionIT {

    static final String EMPTY_EXTRACT = "{\"json\":{\"summary\":\"\",\"mentions\":[],\"statements\":[]}}";

    // ---------------------------------------------------------------- F: secret redaction gap

    @Test
    void secretsInSourceMediaTypeAndContentHashReachTheGraph() {
        String key = "sk-" + "abcdefghijklmnopqrstuvwxyz012345";
        String gh = "ghp_" + "abcdefghijklmnopqrstuvwxyz0123456789";
        String text = "[[fake-llm:srcsecret]] GraphSpindle uses Neo4j.";
        fake.script("srcsecret", text, "extract-v1", EMPTY_EXTRACT);
        fake.script("srcsecret", text, "reconcile-v1", """
                {"json":{"summary":"GraphSpindle uses Neo4j.","operations":[
                 {"op":"create_entity","ref":"e1","type":"Project","name":"GraphSpindle"},
                 {"op":"create_entity","ref":"e2","type":"Technology","name":"Neo4j"},
                 {"op":"assert_claim","subject":{"ref":"e1"},"predicate":"USES","object":{"ref":"e2"},"statement":"GraphSpindle uses Neo4j.","evidence":"GraphSpindle uses Neo4j","confidence":0.9}]}}""");
        Map<String, Object> source = Map.of("kind", "FILE", "locator", "notes/a.md", "mediaType", key,
                "contentHash", gh);
        Response r = ingest("dev", Map.of("content", text, "sources", List.of(source)), "");
        // adapted to the fix (ADR-031): stored-as-given fields are refused, not silently stored
        assertThat(r.status()).as(r.body().toString()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(count("MATCH (n {brainId:'dev'}) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS $a"
                + " OR toString(n[k]) CONTAINS $b) RETURN count(n)", Map.of("a", key, "b", gh)))
                .as("secret stored on a node of the graph").isZero();
    }

    // ---------------------------------------------------------------- F: correction cue anywhere in the text

    @Test
    void correctionCueInAnUnrelatedSentenceLetsAnImplicitContradictionSupersede() {
        assertThat(ingest("dev", "readme-example", null).status()).isEqualTo(201);
        // "moved ... to" refers to a meeting, not to the license
        String text = "I moved the standup to Friday. GraphSpindle is GPL-3.0 licensed.";
        fake.script("cue-elsewhere", text, "extract-v1", "{\"json\":{\"summary\":\"GPL\",\"mentions\":[{\"localRef\":"
                + "\"m1\",\"name\":\"GraphSpindle\",\"type\":\"Project\",\"evidence\":\"GraphSpindle is\"}],"
                + "\"statements\":[]}}");
        fake.script("cue-elsewhere", text, "reconcile-v1", """
                {"json":{"summary":"GPL","operations":[
                 {"op":"create_entity","ref":"g1","type":"Standard","name":"GNU General Public License v3.0","aliases":["GPL-3.0"]},
                 {"op":"supersede_claim","claimId":"@claim:GraphSpindle|LICENSED_UNDER","explicitCorrection":true,"reason":"The license is GPL-3.0",
                  "replacement":{"subject":{"id":"@entity:GraphSpindle"},"predicate":"LICENSED_UNDER","object":{"ref":"g1"},
                   "statement":"GraphSpindle is licensed under GPL-3.0.","evidence":"GraphSpindle is GPL-3.0 licensed","confidence":0.9}}]}}
                """);
        Response r = ingest("dev", Map.of("content", text), "");
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("accepted").toString()).as("implicit contradiction must be a conflict")
                .doesNotContain("CLAIM_SUPERSEDED");
    }

    // ---------------------------------------------------------------- F: DECISION_IMMUTABLE bypass

    @Test
    void latestWinsLetsAnAssertClaimRewriteADecisionsChoice() {
        assertThat(api().patch("/api/v1/brains/dev", "{\"config\":{\"conflictPolicy\":\"LATEST_WINS\"}}", null)
                .status()).isEqualTo(200);
        assertThat(ingest("dev", "readme-example", null).status()).isEqualTo(201);
        String decision = "Use Apache License 2.0 for GraphSpindle";
        String text = "The license decision for GraphSpindle chooses MIT.";
        fake.script("decision-rewrite", text, "extract-v1", "{\"json\":{\"summary\":\"\",\"mentions\":[{\"localRef\":"
                + "\"m1\",\"name\":\"" + decision + "\",\"type\":\"Decision\",\"evidence\":\"license decision\"}],"
                + "\"statements\":[{\"subjectRef\":\"m1\",\"predicate\":\"CHOOSES\",\"statement\":\"x\",\"evidence\":\"x\","
                + "\"confidence\":0.9}]}}");
        fake.script("decision-rewrite", text, "reconcile-v1", """
                {"json":{"summary":"MIT","operations":[
                 {"op":"create_entity","ref":"e1","type":"Standard","name":"MIT License","aliases":["MIT"]},
                 {"op":"assert_claim","subject":{"id":"@entity:%s"},"predicate":"CHOOSES","object":{"ref":"e1"},
                  "statement":"The decision chooses MIT.","evidence":"GraphSpindle chooses MIT","confidence":0.9}]}}
                """.formatted(decision));
        Response r = ingest("dev", Map.of("content", text), "");
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        List<Map<String, Object>> chooses = query("""
                MATCH (c:Claim {brainId:'dev', predicate:'CHOOSES'})-[:OBJECT]->(o:Entity)
                RETURN o.name AS o, c.status AS s ORDER BY c.recordedAt""", Map.of());
        // ADR-028 §6: a Decision's CHOOSES is never rewritten; a new Decision must REPLACE it
        assertThat(chooses).extracting(m -> m.get("o") + "=" + m.get("s")).contains("Apache License 2.0=ACTIVE");
    }

    // ---------------------------------------------------------------- F: RECEIVED forever

    @Test
    void anIngestionLeftReceivedBlocksItsRequestIdForever() {
        // a crash / restart / un-caught Error between createReceived and markFailed leaves this behind
        String text = "[[fake-llm:stuck]] GraphSpindle uses Neo4j.";
        fake.script("stuck", text, "extract-v1", EMPTY_EXTRACT);
        fake.script("stuck", text, "reconcile-v1", "{\"json\":{\"summary\":\"x\",\"operations\":[]}}");
        Response first = ingest("dev", Map.of("content", text, "requestId", "r-stuck"), "");
        assertThat(first.status()).isIn(200, 201);
        write("MATCH (i:Ingestion {brainId:'dev', requestId:'r-stuck'}) SET i.status = 'RECEIVED', "
                + " i.receivedAt = datetime() - duration({days: 7}) REMOVE i.result", Map.of());
        fake.script("stuck", text, "extract-v1", EMPTY_EXTRACT);
        fake.script("stuck", text, "reconcile-v1", "{\"json\":{\"summary\":\"x\",\"operations\":[]}}");
        Response retry = ingest("dev", Map.of("content", text, "requestId", "r-stuck"), "");
        assertThat(retry.status()).as("a week-old RECEIVED ingestion should not block retries: " + retry.body())
                .isNotEqualTo(409);
    }
}
