package io.graphspindle.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractIngestionIT;
import io.graphspindle.support.ApiClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * M7 acceptance: two ingestions creating the same new entity in parallel yield exactly one entity and
 * both ingestions are APPLIED (writes to one brain are serialized by the Brain lock and entities are
 * MERGEd by canonical key; a uniqueness race is retried once, {@code ConstraintRetryIT}). A parallel
 * ingestion into another brain creates its own entity.
 */
class ConcurrentIngestionIT extends AbstractIngestionIT {

    static String text(int i) {
        return "Team " + i + " adopted Kafka Streams for GraphSpindle.";
    }

    private void scriptAdoption(int i) {
        script(text(i), List.of("Kafka Streams:Technology", "GraphSpindle:Project"), """
                [{"op":"create_entity","ref":"t","type":"Technology","name":"Kafka Streams"},
                 {"op":"create_entity","ref":"p","type":"Project","name":"GraphSpindle"},
                 {"op":"assert_claim","subject":{"ref":"p"},"predicate":"USES","object":{"ref":"t"},
                  "statement":"GraphSpindle uses Kafka Streams.","evidence":"adopted Kafka Streams for GraphSpindle",
                  "confidence":0.9}]""");
    }

    @Test
    void parallelCreationsOfTheSameEntityYieldOneEntity() throws Exception {
        for (int i = 1; i <= 4; i++) {
            scriptAdoption(i);
        }
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<ApiClient.Response>> runs = List.of(1, 2, 3).stream()
                .map(i -> CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return ingest(i == 3 ? "third" : "dev", Map.of("content", text(i)), "");
                })).toList();
        k.brain("third");
        start.countDown();
        for (CompletableFuture<ApiClient.Response> run : runs) {
            ApiClient.Response r = run.get(60, TimeUnit.SECONDS);
            assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
            assertThat(r.body().get("status").asString()).isEqualTo("APPLIED");
        }
        assertThat(count("MATCH (n:Entity {brainId:'dev', name:'Kafka Streams'}) RETURN count(n)", Map.of()))
                .isEqualTo(1);
        assertThat(count("MATCH (n:Entity {brainId:'dev', name:'GraphSpindle'}) RETURN count(n)", Map.of()))
                .isEqualTo(1);
        assertThat(count("MATCH (n:Entity {brainId:'third', name:'Kafka Streams'}) RETURN count(n)", Map.of()))
                .isEqualTo(1);
        // the second ingestion reinforced the claim instead of duplicating it
        assertThat(query("MATCH (c:Claim {brainId:'dev', predicate:'USES', status:'ACTIVE'}) RETURN c.evidenceCount AS n",
                Map.of())).extracting(m -> m.get("n")).containsExactly(2L);
        assertThat(count("MATCH (i:Ingestion {brainId:'dev', status:'APPLIED'}) RETURN count(i)", Map.of()))
                .isEqualTo(2);
        assertOtherBrainUntouched();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
