package io.graphspindle.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.llm.LlmProperties;
import io.graphspindle.support.AbstractIngestionIT;
import io.graphspindle.support.ApiClient.Response;
import io.graphspindle.llm.FakeChatModel;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * QA cc4929c: TokenEstimator counts 3 ASCII chars per token, but the default model (gemma4:12b,
 * measured with Ollama prompt_eval_count) spends one token per DIGIT, ~2.2 tokens per 3 chars of a
 * sensor log, 2-4 per CJK char. A numeric log passes both gates and is sent although it overflows
 * num-ctx, which is exactly the silent head truncation ADR-031 was meant to prevent.
 */
class QaM8BudgetIT extends AbstractIngestionIT {

    @Autowired
    LlmProperties llm;

    static void dump(FakeChatModel.Call c) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("target", "qa-prompts");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve(c.stage() + ".system.txt"), c.system());
            java.nio.file.Files.writeString(dir.resolve(c.stage() + ".user.txt"), c.user());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void aNumericLogThatOverflowsTheContextIsNeverSent() {
        Random r = new Random(2);
        StringBuilder sb = new StringBuilder("[[fake-llm:digits]] Sensor log of GraphSpindle:\n");
        while (sb.length() < 30_000) {
            sb.append(String.format("sensor %d: %d, %d, %d, %d, %d, %d%n", 100 + r.nextInt(900),
                    r.nextInt(100_000), r.nextInt(100_000), r.nextInt(100_000), r.nextInt(100_000),
                    r.nextInt(100_000), r.nextInt(100_000)));
        }
        String text = sb.toString();
        fake.script("digits", text, "extract-v1", "{\"json\":{\"summary\":\"\",\"mentions\":[],\"statements\":[]}}");
        fake.script("digits", text, "reconcile-v1", "{\"json\":{\"summary\":\"x\",\"operations\":[]}}");
        Response resp = ingest("dev", Map.of("content", text), "");
        long budget = llm.contextTokens() - llm.maxOutputTokens();
        for (FakeChatModel.Call c : fake.calls()) {
            long digits = c.text().chars().filter(Character::isDigit).count();
            dump(c);
            // gemma4: >= 1 token per digit, so this call alone overflows num-ctx
            long estimate = io.graphspindle.llm.TokenEstimator.estimate(c.system())
                    + io.graphspindle.llm.TokenEstimator.estimate(c.user());
            // measured with Ollama gemma4:12b (prompt_eval_count): extract 30,838 and reconcile 34,310 tokens,
            // i.e. both overflow num-ctx 32,768 (minus 4,096 output); a pessimistic estimate must at least
            // count the digits, which the default tokenizer spends one token each on
            assertThat(estimate).as("%s: estimate vs digits", c.stage()).isGreaterThanOrEqualTo(digits);
        }
        // the estimate now refuses the oversize prompt before any call is made
        assertThat(resp.status()).isNotEqualTo(201);
        assertThat(budget).isPositive();
    }
}
