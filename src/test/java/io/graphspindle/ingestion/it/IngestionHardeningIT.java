package io.graphspindle.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.ingestion.StaleIngestionSweeper;
import io.graphspindle.support.AbstractIngestionIT;
import io.graphspindle.support.ApiClient.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** ADR-031: prompt budget, retry of transient failures, stale sweep, source field validation. */
class IngestionHardeningIT extends AbstractIngestionIT {

    static final String EMPTY_EXTRACT = "{\"json\":{\"summary\":\"\",\"mentions\":[],\"statements\":[]}}";

    @Autowired
    StaleIngestionSweeper sweeper;

    @Test
    void aPromptThatCannotFitIsNeverSent() {
        // 30,000 non-ASCII characters pass admission (chars / 2) but not the per-call estimate (1 token each)
        String text = "漢".repeat(30_000);
        Response r = ingest("dev", Map.of("content", text, "requestId", "r-big"), "");
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(400);
        assertThat(r.body().get("status").asString()).isEqualTo("FAILED");
        assertThat(r.body().get("error").get("code").asString()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(fake.calls()).isEmpty();
        assertThat(nodes("dev", "Claim")).isZero();
    }

    @Test
    void aRequestIdThatFailedWithLlmUnavailableCanBeRetried() {
        String text = "[[fake-llm:retry]] GraphSpindle uses Neo4j.";
        fake.script("retry", text, "extract-v1", "{\"error\":\"io\"}");
        Response first = ingest("dev", Map.of("content", text, "requestId", "r-retry"), "");
        assertThat(first.status()).isEqualTo(503);
        String failedId = first.body().get("ingestionId").asString();

        fake.script("retry", text, "extract-v1", EMPTY_EXTRACT);
        fake.script("retry", text, "reconcile-v1", """
                {"json":{"summary":"uses","operations":[
                 {"op":"create_entity","ref":"e1","type":"Project","name":"GraphSpindle"},
                 {"op":"create_entity","ref":"e2","type":"Technology","name":"Neo4j"},
                 {"op":"assert_claim","subject":{"ref":"e1"},"predicate":"USES","object":{"ref":"e2"},
                  "statement":"GraphSpindle uses Neo4j.","evidence":"GraphSpindle uses Neo4j","confidence":0.9}]}}""");
        Response retry = ingest("dev", Map.of("content", text, "requestId", "r-retry"), "");
        assertThat(retry.status()).as(String.valueOf(retry.body())).isEqualTo(201);
        assertThat(retry.body().get("ingestionId").asString()).isNotEqualTo(failedId);
        assertThat(api().get("/api/v1/brains/dev/ingestions?requestId=r-retry").body().get("ingestionId").asString())
                .isEqualTo(retry.body().get("ingestionId").asString());
        // the old record keeps its failure and the released requestId
        assertThat(query("MATCH (i:Ingestion {brainId:'dev', id:$id}) RETURN i.status AS s, i.releasedRequestId AS r",
                Map.of("id", failedId))).containsExactly(Map.of("s", "FAILED", "r", "r-retry"));
        // a replay of the successful request is a replay, not a third run
        Response again = ingest("dev", Map.of("content", text, "requestId", "r-retry"), "");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("replayed").asBoolean()).isTrue();
    }

    @Test
    void otherFailuresStillReplay() {
        Response r = ingest("dev", "garbage-output", "r-garbage");
        assertThat(r.status()).isEqualTo(502);
        Response again = ingest("dev", "garbage-output", "r-garbage");
        assertThat(again.status()).isEqualTo(502);
        assertThat(again.body().get("replayed").asBoolean()).isTrue();
    }

    @Test
    void theStartupSweepFailsStaleReceivedIngestionsOnly() {
        String text = "[[fake-llm:sweep]] GraphSpindle uses Neo4j.";
        fake.script("sweep", text, "extract-v1", EMPTY_EXTRACT);
        fake.script("sweep", text, "reconcile-v1", "{\"json\":{\"summary\":\"x\",\"operations\":[]}}");
        assertThat(ingest("dev", Map.of("content", text, "requestId", "r-old"), "").status()).isIn(200, 201);
        write("MATCH (i:Ingestion {brainId:'dev', requestId:'r-old'}) SET i.status = 'RECEIVED',"
                + " i.receivedAt = datetime() - duration({hours: 2}) REMOVE i.result", Map.of());
        write("MATCH (b:Brain {id:'dev'}) CREATE (i:Ingestion {brainId:'dev', id:'fresh', requestId:'r-fresh',"
                + " status:'RECEIVED', receivedAt: datetime(), contentHash:'h'})", Map.of());
        sweeper.sweep();
        assertThat(query("MATCH (i:Ingestion {brainId:'dev'}) RETURN i.requestId AS r, i.status AS s ORDER BY r",
                Map.of())).extracting(m -> m.get("r") + "=" + m.get("s"))
                .containsExactly("r-fresh=RECEIVED", "r-old=FAILED");
        Response failed = api().get("/api/v1/brains/dev/ingestions?requestId=r-old");
        assertThat(failed.body().get("error").get("code").asString()).isEqualTo("STALE");
    }

    @Test
    void wellFormedSourceFieldsAreStored() {
        String text = "[[fake-llm:srcok]] GraphSpindle uses Neo4j.";
        fake.script("srcok", text, "extract-v1", EMPTY_EXTRACT);
        fake.script("srcok", text, "reconcile-v1", "{\"json\":{\"summary\":\"x\",\"operations\":[]}}");
        String hash = "sha256:" + "ab".repeat(32);
        Map<String, Object> source = Map.of("kind", "FILE", "locator", "notes/a.md", "mediaType", "Text/Markdown",
                "contentHash", hash);
        Response r = ingest("dev", Map.of("content", text, "sources", List.of(source)), "");
        assertThat(r.status()).as(String.valueOf(r.body())).isIn(200, 201);
    }

    @Test
    void latestWinsFlagNeverRewritesADecision() {
        assertThat(api().patch("/api/v1/brains/dev", "{\"config\":{\"conflictPolicy\":\"LATEST_WINS\"}}", null)
                .status()).isEqualTo(200);
        assertThat(ingest("dev", "readme-example", null).status()).isEqualTo(201);
        String decision = "Use Apache License 2.0 for GraphSpindle";
        String text = "The license decision for GraphSpindle chooses MIT.";
        fake.script("decision-flag", text, "extract-v1", "{\"json\":{\"summary\":\"\",\"mentions\":[{\"localRef\":"
                + "\"m1\",\"name\":\"" + decision + "\",\"type\":\"Decision\",\"evidence\":\"license decision\"}],"
                + "\"statements\":[{\"subjectRef\":\"m1\",\"predicate\":\"CHOOSES\",\"statement\":\"x\",\"evidence\":\"x\","
                + "\"confidence\":0.9}]}}");
        fake.script("decision-flag", text, "reconcile-v1", """
                {"json":{"summary":"MIT","operations":[
                 {"op":"create_entity","ref":"e1","type":"Standard","name":"MIT License","aliases":["MIT"]},
                 {"op":"flag_conflict","claimId":"@claim:%s|CHOOSES","description":"different license",
                  "incoming":{"subject":{"id":"@entity:%s"},"predicate":"CHOOSES","object":{"ref":"e1"},
                  "statement":"The decision chooses MIT.","evidence":"GraphSpindle chooses MIT","confidence":0.9}}]}}
                """.formatted(decision, decision));
        Response r = ingest("dev", Map.of("content", text), "");
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        assertThat(claimStates("dev", decision, "CHOOSES")).contains("Apache License 2.0=ACTIVE", "MIT License=DISPUTED");
    }
}
