package io.graphspindle.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractIngestionIT;
import io.graphspindle.support.ApiClient.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M6 acceptance: malformed output (repaired / failed), chatter, LLM timeout, dry run. */
class PipelineFailureIT extends AbstractIngestionIT {

    @Test
    void brokenJsonIsRepairedOnceWithTheFaultPosition() {
        Response r = ingest("dev", "malformed-then-repaired", null);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("status").asString()).isEqualTo("APPLIED");
        assertThat(r.body().get("llm").get("calls").asInt()).isEqualTo(3);
        assertThat(r.body().get("llm").get("repairs").asInt()).isEqualTo(1);
        assertThat(fake.calls("repair-v1")).singleElement().satisfies(c -> {
            assertThat(c.user()).contains("line ").contains("column ");
            assertThat(c.user()).containsPattern("<<<DATA nonce=[0-9a-f]{16} name=previous_answer>>>");
        });
        assertThat(claims("dev", "ACTIVE")).isPositive();
        assertOtherBrainUntouched();
    }

    @Test
    void garbageTwiceFailsCleanlyWithoutKnowledge() {
        Response r = ingest("dev", "garbage-output", "garbage-1");
        assertThat(r.status()).isEqualTo(502);
        assertThat(r.body().get("status").asString()).isEqualTo("FAILED");
        assertThat(r.body().get("error").get("code").asString()).isEqualTo("MALFORMED_MODEL_OUTPUT");
        assertThat(r.body().get("llm").get("calls").asInt()).isEqualTo(3);
        assertThat(query("MATCH (i:Ingestion {brainId:'dev'}) RETURN i.status AS s, i.report AS rep", Map.of()))
                .singleElement().satisfies(m -> {
                    assertThat(m.get("s")).isEqualTo("FAILED");
                    assertThat(m.get("rep").toString()).contains("MALFORMED_MODEL_OUTPUT");
                });
        // no knowledge: only the self entity, no claims, sources or conflicts
        assertThat(nodes("dev", "Entity")).isEqualTo(1);
        assertThat(nodes("dev", "Claim")).isZero();
        assertThat(nodes("dev", "SourceRef")).isZero();
        assertThat(nodes("dev", "Conflict")).isZero();
        // a replay returns the stored failure without calling the model again
        int calls = fake.callCount();
        Response replay = ingest("dev", "garbage-output", "garbage-1");
        assertThat(replay.body().get("replayed").asBoolean()).isTrue();
        assertThat(replay.body().get("status").asString()).isEqualTo("FAILED");
        assertThat(fake.callCount()).isEqualTo(calls);
        assertOtherBrainUntouched();
    }

    @Test
    void chatterChangesNothing() {
        Response r = ingest("dev", "chatter-no-knowledge", null);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("status").asString()).isEqualTo("NO_CHANGES");
        assertThat(r.body().get("opsProposed").asInt()).isZero();
        assertThat(nodes("dev", "Entity")).isEqualTo(1);
        assertThat(nodes("dev", "Claim")).isZero();
        assertOtherBrainUntouched();
    }

    @Test
    void llmTimeoutFailsTheIngestionWith503AndWritesNothing() {
        Response r = ingest("dev", "llm-timeout", null);
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.body().get("status").asString()).isEqualTo("FAILED");
        assertThat(r.body().get("error").get("code").asString()).isEqualTo("LLM_UNAVAILABLE");
        assertThat(r.body().get("error").get("message").asString()).contains("timed out");
        assertThat(query("MATCH (i:Ingestion {brainId:'dev'}) RETURN i.status AS s", Map.of()))
                .extracting(m -> m.get("s")).containsExactly("FAILED");
        assertThat(nodes("dev", "Claim")).isZero();
        assertThat(nodes("dev", "Entity")).isEqualTo(1);
        assertOtherBrainUntouched();
    }

    @Test
    void dryRunReturnsThePlanAndPersistsNothing() {
        long before = count("MATCH (n) RETURN count(n)", Map.of());
        Response r = ingest("dev", Map.of("content", fixture("readme-example").get("content").asString(),
                "requestId", "dry-1"), "?dryRun=true");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("status").asString()).isEqualTo("DRY_RUN");
        assertThat(r.body().get("dryRun").asBoolean()).isTrue();
        assertThat(r.body().get("planned").size()).isGreaterThanOrEqualTo(19);
        assertThat(r.body().get("ingestionId").isNull()).isTrue();
        assertThat(count("MATCH (n) RETURN count(n)", Map.of())).isEqualTo(before);
        assertThat(api().get("/api/v1/brains/dev/ingestions?requestId=dry-1").status()).isEqualTo(404);
        assertOtherBrainUntouched();
    }
}
