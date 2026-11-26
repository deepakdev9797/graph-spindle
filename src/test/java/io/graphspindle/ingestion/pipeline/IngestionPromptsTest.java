package io.graphspindle.ingestion.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.common.Limits;
import io.graphspindle.ingestion.pipeline.IngestionPrompts.RenderedPrompt;
import io.graphspindle.vocabulary.OutputSchemaRenderer;
import io.graphspindle.vocabulary.PromptVocabularyRenderer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class IngestionPromptsTest {

    private final IngestionPrompts prompts = new IngestionPrompts();
    private final Limits limits = Limits.defaults();

    @Test
    void systemMessagesEmbedVocabularyAndSchemaAndNoRequestData() {
        Scenario s = Scenario.named("followup-update");
        RenderedPrompt extract = prompts.extract(s.input(limits, null));
        RenderedPrompt reconcile = prompts.reconcile(s.input(limits, "{\"statements\":[]}"));
        String vocabulary = new PromptVocabularyRenderer().render(Scenario.CORE).strip();
        assertThat(extract.system()).startsWith("GraphSpindle prompt extract-v1.").contains(vocabulary)
                .contains(prompts.extractionSchema(Scenario.CORE, limits));
        assertThat(reconcile.system()).startsWith("GraphSpindle prompt reconcile-v1.").contains(vocabulary)
                .contains(prompts.proposalSchema(Scenario.CORE, limits));
        assertThat(JsonMapper.builder().build().readTree(prompts.proposalSchema(Scenario.CORE, limits)))
                .isEqualTo(new OutputSchemaRenderer().renderNode(Scenario.CORE, limits));
        for (RenderedPrompt p : new RenderedPrompt[] {extract, reconcile}) {
            assertThat(p.system()).doesNotContain("is helping me").doesNotContain("ARCHITECTURE.md")
                    .doesNotContain("{\"id\":\"k1\",\"type\":\"Project\"");
            assertThat(p.user()).contains(s.content());
        }
    }

    @Test
    void systemMessageIsStablePerBrainApartFromTheNonce() {
        Scenario a = Scenario.named("readme-example");
        Scenario b = Scenario.named("meeting-notes");
        RenderedPrompt pa = prompts.reconcile(a.input(limits, "{}"));
        RenderedPrompt pb = prompts.reconcile(b.input(limits, "{}"));
        assertThat(pa.system().replace(pa.nonce(), "N")).isEqualTo(pb.system().replace(pb.nonce(), "N"));
    }

    @Test
    void candidatesAreShownByHandleOnlyAndSelfIsP0() {
        Scenario s = Scenario.named("explicit-correction");
        RenderedPrompt p = prompts.reconcile(s.input(limits, "{}"));
        assertThat(p.user()).contains("{\"id\":\"k1\",\"type\":\"Project\",\"name\":\"GraphSpindle\"")
                .contains("{\"claimId\":\"c1\",\"subject\":{\"id\":\"k1\"},\"predicate\":\"LICENSED_UNDER\",\"object\":{\"id\":\"k2\"}")
                .contains("\"id\":\"P0\"").doesNotContain("id-k1").doesNotContain("person:@self");
        assertThat(p.system()).contains("The speaker is always {\"id\":\"P0\"}");
    }

    @Test
    void speakerAndSourcesAreDataBlocks() {
        Scenario s = Scenario.named("implicit-contradiction");
        RenderedPrompt p = prompts.extract(s.input(limits, null));
        assertThat(p.user()).contains("<<<DATA nonce=" + p.nonce() + " name=speaker>>>")
                .contains("\"name\":\"ci-bot\"");
        Scenario f = Scenario.named("file-reference");
        RenderedPrompt q = prompts.reconcile(f.input(limits, "{}"));
        assertThat(q.user()).contains("<<<DATA nonce=" + q.nonce() + " name=sources>>>");
    }

    @Test
    void syntaxHintPointsAtTheFault() {
        String raw = "{\"operations\":[{\"op\":\"create_entity\",\"name\":\"Initech\",\"aliases[]}]}";
        assertThat(prompts.syntaxHint(raw, "bad")).startsWith("bad; JSON syntax error at line 1 column")
                .contains("aliases[]");
        assertThat(prompts.syntaxHint("no json here", "bad")).contains("no JSON object");
    }

    @Test
    void schemasAreValidJson() {
        JsonMapper json = JsonMapper.builder().build();
        assertThat(json.readTree(prompts.extractionSchema(Scenario.CORE, limits)).path("properties").has("statements"))
                .isTrue();
        RenderedPrompt r = prompts.repair(IngestionPrompts.RECONCILE, Scenario.CORE, limits, "x".repeat(20_000), "bad");
        assertThat(r.system()).startsWith("GraphSpindle prompt repair-v1.").contains("{\"summary\":\"\",\"operations\":[]}");
        assertThat(r.user()).hasSizeLessThan(IngestionPrompts.REPAIR_MAX_PREVIOUS_CHARS + 1_000);
    }
}
