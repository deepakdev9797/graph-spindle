package io.graphspindle.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractIngestionIT;
import io.graphspindle.support.ApiClient.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** M6 acceptance: requestId idempotency and content-hash deduplication (ARCHITECTURE §6). */
class IdempotencyIT extends AbstractIngestionIT {

    @Test
    void replayReturnsTheStoredResultWithoutCallingTheModel() {
        Response first = ingest("dev", "readme-example", "req-1");
        assertThat(first.status()).isEqualTo(201);
        int calls = fake.callCount();
        Response replay = ingest("dev", "readme-example", "req-1");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("replayed").asBoolean()).isTrue();
        assertThat(replay.body().get("ingestionId").asString()).isEqualTo(first.body().get("ingestionId").asString());
        assertThat(replay.body().get("accepted")).isEqualTo(first.body().get("accepted"));
        assertThat(fake.callCount()).isEqualTo(calls);
        assertThat(nodes("dev", "Ingestion")).isEqualTo(1);
        assertThat(claims("dev", "ACTIVE")).isEqualTo(13);
        assertOtherBrainUntouched();
    }

    @Test
    void sameRequestIdWithDifferentContentIs409() {
        assertThat(ingest("dev", "readme-example", "req-2").status()).isEqualTo(201);
        Response r = ingest("dev", "chatter-no-knowledge", "req-2");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        // the same requestId in another brain is independent
        assertThat(ingest("other", "chatter-no-knowledge", "req-2").status()).isEqualTo(201);
    }

    @Test
    void sameContentWithANewRequestIdIsADuplicateUnlessForced() {
        Response first = ingest("dev", "readme-example", "req-3");
        int calls = fake.callCount();
        Response dup = ingest("dev", "readme-example", "req-4");
        assertThat(dup.status()).isEqualTo(200);
        assertThat(dup.body().get("duplicateOf").asString()).isEqualTo(first.body().get("ingestionId").asString());
        assertThat(fake.callCount()).isEqualTo(calls);
        assertThat(nodes("dev", "Ingestion")).isEqualTo(1);

        fake.reset();
        Response forced = ingest("dev", Map.of("content", fixture("readme-example").get("content").asString(),
                "requestId", "req-5"), "?force=true");
        assertThat(forced.status()).isEqualTo(201);
        assertThat(forced.body().get("duplicateOf").isNull()).isTrue();
        // everything is reinforced, nothing duplicated
        assertThat(nodes("dev", "Entity")).isEqualTo(7);
        assertThat(claims("dev", "ACTIVE")).isEqualTo(13);
        assertThat(forced.body().get("accepted").toString()).contains("CLAIM_REINFORCED").doesNotContain("CLAIM_CREATED");
    }

    @Test
    void concurrentIdenticalRequestsApplyOnce() throws Exception {
        String content = "[[fake-llm:slow]] GraphSpindle is written in Java.";
        fake.script("slow", content, "extract-v1", "{\"delayMs\":700,\"json\":{\"summary\":\"\",\"mentions\":[],"
                + "\"statements\":[]}}");
        fake.script("slow", content, "reconcile-v1", """
                {"json":{"operations":[
                 {"op":"create_entity","ref":"e1","type":"Project","name":"GraphSpindle"},
                 {"op":"create_entity","ref":"e2","type":"Technology","name":"Java"},
                 {"op":"assert_claim","subject":{"ref":"e1"},"predicate":"WRITTEN_IN","object":{"ref":"e2"},"statement":"GraphSpindle is written in Java.","evidence":"GraphSpindle is written in Java","confidence":0.9}]}}""");
        Map<String, Object> body = Map.of("content", content, "requestId", "same");
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<Response>> fs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                fs.add(CompletableFuture.supplyAsync(() -> ingest("dev", body, ""), pool));
                Thread.sleep(100);
            }
            List<Integer> statuses = fs.stream().map(CompletableFuture::join).map(Response::status).sorted().toList();
            assertThat(statuses).containsExactly(201, 409);
            assertThat(fs.stream().map(CompletableFuture::join).filter(r -> r.status() == 409).findFirst().get().code())
                    .isEqualTo("IN_PROGRESS");
        } finally {
            pool.shutdownNow();
        }
        assertThat(nodes("dev", "Ingestion")).isEqualTo(1);
        assertThat(fake.calls("extract-v1")).hasSize(1);
        assertThat(claims("dev", "ACTIVE")).isEqualTo(1);
        // once finished, the same request replays
        assertThat(ingest("dev", body, "").body().get("replayed").asBoolean()).isTrue();
        assertOtherBrainUntouched();
    }
}
