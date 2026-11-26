package io.graphspindle.ingestion.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.common.Limits;
import io.graphspindle.ingestion.IngestionProperties;
import io.graphspindle.ingestion.pipeline.IngestionPrompts.PromptClaim;
import io.graphspindle.ingestion.pipeline.IngestionPrompts.PromptEntity;
import io.graphspindle.llm.TokenEstimator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-031: candidate/extraction budgets and correction cues bound to the evidence sentence. */
class PromptBudgetAndCueProximityTest {

    final CorrectionCues cues = new CorrectionCues(IngestionProperties.DEFAULT_CORRECTION_CUES);

    // ------------------------------------------------------------------ cue proximity

    @Test
    void cueMustBeInTheEvidenceSentenceOrAShortLeadSentence() {
        String far = "Actually, the standup is on Friday and the coffee machine is broken again today. "
                + "GraphSpindle is GPL-3.0 licensed.";
        assertThat(cues.present(far)).isTrue();
        assertThat(cues.presentNear(far, "GraphSpindle is GPL-3.0 licensed")).isFalse();
        assertThat(cues.presentNear("Correction. GraphSpindle is GPL-3.0 licensed.",
                "GraphSpindle is GPL-3.0 licensed")).isTrue();
        assertThat(cues.presentNear("Change of plan: we're switching GraphSpindle from Apache 2.0 to the MIT license.",
                "switching GraphSpindle from Apache 2.0 to the MIT license")).isTrue();
        assertThat(cues.presentNear("We no longer use Kafka in GraphSpindle.", "no longer use Kafka")).isTrue();
    }

    @Test
    void quotedTextIsNotTheSpeakersCorrection() {
        assertThat(cues.present("He wrote \"actually it is MIT\". GraphSpindle is GPL-3.0 licensed.")).isFalse();
        assertThat(cues.present("> correction: MIT\nGraphSpindle is GPL-3.0 licensed.")).isFalse();
        assertThat(cues.present("GraphSpindle is not just fast but GPL-3.0 licensed.")).isFalse();
        assertThat(cues.present("We changed the license to MIT.")).isTrue();
        assertThat(cues.present("We moved the meeting to 3pm.")).isFalse();
        assertThat(cues.present("We moved Ferrite to SQLite.")).isTrue();
    }

    @Test
    void evidenceOutsideTheContentFallsBackToTheWholeContent() {
        assertThat(cues.presentNear("Actually it's MIT.", "a quote from a source summary")).isTrue();
    }

    // ------------------------------------------------------------------ budgets

    @Test
    void lowRankedCandidatesAreDroppedFirstAndClaimsStayConsistent() {
        IngestionPrompts prompts = new IngestionPrompts(500, 500);
        List<PromptEntity> entities = new ArrayList<>();
        entities.add(new PromptEntity("P0", "Person", "the speaker", List.of(), null, 2.0));
        entities.add(new PromptEntity("k1", "Project", "Best match", List.of(), "x".repeat(300), 1.0));
        for (int i = 2; i <= 30; i++) {
            entities.add(new PromptEntity("k" + i, "Project", "Weak " + i, List.of("a".repeat(150), "b", "c", "d"),
                    "y".repeat(300), 0.2));
        }
        entities.add(new PromptEntity("k31", "Standard", "Context object", List.of(), null, null));
        List<PromptClaim> claims = new ArrayList<>();
        claims.add(new PromptClaim("c1", "k1", "LICENSED_UNDER", "k31", null, "POSITIVE", "s".repeat(400), "LLM"));
        for (int i = 2; i <= 30; i++) {
            claims.add(new PromptClaim("c" + i, "k" + i, "USES", "k1", null, "POSITIVE", "t".repeat(400), "LLM"));
        }
        String text = prompts.candidatesText(entities, claims);
        assertThat(TokenEstimator.estimate(text)).isLessThanOrEqualTo(600);
        assertThat(text).contains("\"id\":\"P0\"", "\"id\":\"k1\"", "\"claimId\":\"c1\"", "\"id\":\"k31\"")
                .contains("omitted for length").doesNotContain("\"id\":\"k30\"");
        assertThat(text).doesNotContain("s".repeat(201)).doesNotContain("a".repeat(101));
        // every claim shown refers only to entities shown
        for (String line : text.split("\n")) {
            if (line.contains("claimId")) {
                String subject = line.replaceAll(".*\"subject\":\\{\"id\":\"([^\"]+)\".*", "$1");
                assertThat(text).contains("{\"id\":\"" + subject + "\"");
            }
        }
    }

    @Test
    void extractionHintIsBoundedAndStaysJson() {
        IngestionPrompts prompts = new IngestionPrompts(500, 200);
        StringBuilder sb = new StringBuilder("{\"summary\":\"s\",\"mentions\":[");
        for (int i = 0; i < 50; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"localRef\":\"m").append(i).append("\",\"name\":\"N").append(i)
                    .append("\"}");
        }
        sb.append("],\"statements\":[");
        for (int i = 0; i < 50; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"statement\":\"").append("z".repeat(100)).append("\"}");
        }
        sb.append("]}");
        String bounded = prompts.boundedExtraction(sb.toString());
        assertThat(TokenEstimator.estimate(bounded)).isLessThanOrEqualTo(200);
        assertThat(bounded).startsWith("{\"summary\":\"s\"").contains("\"mentions\":[{\"localRef\":\"m0\"");
        assertThat(prompts.boundedExtraction("{\"summary\":\"short\",\"mentions\":[],\"statements\":[]}"))
                .contains("short");
    }

    @Test
    void readmePromptFitsTheDefaultContextWithRoom() {
        IngestionPrompts prompts = new IngestionPrompts();
        IngestionPrompts.Input in = Scenario.named("readme-example").input(Limits.defaults(), "{}");
        var p = prompts.reconcile(in);
        long tokens = TokenEstimator.estimate(p.system()) + TokenEstimator.estimate(p.user());
        assertThat(tokens).isLessThan(32768 - 4096 - IngestionPrompts.DEFAULT_CANDIDATE_TOKENS
                - IngestionPrompts.DEFAULT_EXTRACTION_TOKENS);
    }

    @Test
    void estimateIsPessimistic() {
        assertThat(TokenEstimator.estimate("abcdef")).isEqualTo(2);
        assertThat(TokenEstimator.estimate("日本語")).isEqualTo(3);
        assertThat(TokenEstimator.estimate("")).isZero();
    }
}
