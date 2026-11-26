package io.graphspindle.ingestion.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.common.Limits;
import io.graphspindle.ingestion.pipeline.IngestionPrompts.PromptClaim;
import io.graphspindle.ingestion.pipeline.IngestionPrompts.PromptEntity;
import io.graphspindle.ingestion.pipeline.IngestionPrompts.RenderedPrompt;
import io.graphspindle.llm.LlmProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * QA M6: the admission estimate (12k overhead + chars/2 + max output) is the only guard against
 * prompts that exceed the model context. With Ollama an oversize prompt is silently truncated from
 * the HEAD, i.e. the system message with the SECURITY rules and the nonce is dropped first.
 */
class QaPromptBudgetTest {

    private final IngestionPrompts prompts = new IngestionPrompts();
    private final Limits limits = Limits.defaults();
    private final LlmProperties llm = LlmProperties.defaults();

    /** Conservative token estimate: 1 token per non-ASCII code point, 1 per 4 ASCII chars. */
    static long tokens(String s) {
        long ascii = s.chars().filter(c -> c < 128).count();
        long other = s.codePoints().filter(c -> c >= 128).count();
        return ascii / 4 + other;
    }

    private long budget() {
        return llm.contextTokens() - llm.maxOutputTokens();
    }

    @Test
    void candidateBlockIsUnboundedAndNotPartOfAdmission() {
        // what CandidateStage can legally emit: <= 60 entities (40 + 20 context), <= 120 claims;
        // names 200, 5 aliases x 200, description truncated to 300, statement 500 (not truncated)
        List<PromptEntity> entities = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            entities.add(new PromptEntity("k" + (i + 1), "Project", ("Entity" + i + " ").repeat(40).substring(0, 200),
                    List.of("a".repeat(200), "b".repeat(200), "c".repeat(200), "d".repeat(200), "e".repeat(200)),
                    "d".repeat(1000)));
        }
        List<PromptClaim> claims = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            claims.add(new PromptClaim("c" + (i + 1), "k1", "description", null, "v".repeat(254), "POSITIVE",
                    "s".repeat(500), "LLM"));
        }
        Scenario s = Scenario.named("readme-example");
        IngestionPrompts.Input base = s.input(limits, "{}");
        IngestionPrompts.Input in = new IngestionPrompts.Input(base.vocabulary(), limits, "Short note about Entity1.",
                "NOTE", null, List.of(), entities, claims, "{}");
        RenderedPrompt p = prompts.reconcile(in);
        long t = tokens(p.system()) + tokens(p.user());
        // the request itself is tiny, so admission (12k + 12 + 4096 tokens) accepts it
        assertThat(t).as("reconcile prompt tokens (system %d chars, user %d chars)", p.system().length(),
                p.user().length()).isLessThanOrEqualTo(budget());
    }
}
