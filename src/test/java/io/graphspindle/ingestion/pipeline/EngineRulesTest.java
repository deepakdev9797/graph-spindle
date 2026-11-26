package io.graphspindle.ingestion.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.graphspindle.common.ClaimKind;
import io.graphspindle.common.Normalizer;
import io.graphspindle.common.Origin;
import io.graphspindle.common.Polarity;
import io.graphspindle.ingestion.IngestionProperties;
import io.graphspindle.ingestion.ops.FlagConflict;
import io.graphspindle.ingestion.ops.MalformedModelOutputException;
import io.graphspindle.ingestion.ops.ParsedProposal;
import io.graphspindle.ingestion.ops.ProposalParser;
import io.graphspindle.ingestion.validation.Adjustment;
import io.graphspindle.ingestion.validation.CandidateSet;
import io.graphspindle.ingestion.validation.ClaimCandidate;
import io.graphspindle.ingestion.validation.EntityCandidate;
import io.graphspindle.ingestion.validation.OperationValidator;
import io.graphspindle.ingestion.validation.PlanCommand;
import io.graphspindle.ingestion.validation.RejectionCode;
import io.graphspindle.ingestion.validation.ValidatedPlan;
import io.graphspindle.ingestion.validation.ValidationContext;
import io.graphspindle.vocabulary.BrainVocabulary;
import io.graphspindle.vocabulary.VocabularyRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The engine-side rules added with M6 from the live eval (ADR-028). */
class EngineRulesTest {

    static final BrainVocabulary CORE = new VocabularyRegistry().core();
    final ProposalParser parser = new ProposalParser();
    final OperationValidator validator = new OperationValidator();
    final CorrectionCues cues = new CorrectionCues(IngestionProperties.DEFAULT_CORRECTION_CUES);

    // ------------------------------------------------------------------ correction cues

    @ParameterizedTest
    @ValueSource(strings = {"Change of plan: we're switching GraphSpindle from Apache 2.0 to the MIT license.",
            "Actually, GraphSpindle is MIT licensed.", "Correction: it is MIT.", "We no longer use Kafka.",
            "GraphSpindle now uses PostgreSQL.", "We moved Ferrite to SQLite.", "It is not Apache but MIT.",
            "The license was wrong, it is MIT.", "We use MIT instead.", "ACTUALLY it's MIT"})
    void correctionLanguageIsRecognised(String text) {
        assertThat(cues.present(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GraphSpindle is GPL-3.0 licensed.", "I am building GraphSpindle in Java.",
            "GraphSpindle now exposes an MCP server too.", "The factual record is clear.", "Insteadly is not a word."})
    void plainStatementsHaveNoCue(String text) {
        assertThat(cues.present(text)).isFalse();
    }

    @Test
    void cuesAreConfigurableAndSupportRegex() {
        CorrectionCues custom = new CorrectionCues(List.of("berichtigung", "re:\\bstatt\\b"));
        assertThat(custom.present("Berichtigung: MIT")).isTrue();
        assertThat(custom.present("MIT statt Apache")).isTrue();
        assertThat(custom.present("Actually MIT")).isFalse();
    }

    // ------------------------------------------------------------------ implicit contradiction downgrade

    static final String GPL = "GraphSpindle is GPL-3.0 licensed.";
    static final String SUPERSEDE = """
            {"operations":[
             {"op":"create_entity","ref":"g1","type":"Standard","name":"GNU General Public License v3.0","aliases":["GPL-3.0"]},
             {"op":"supersede_claim","claimId":"c1","explicitCorrection":true,"reason":"License is GPL",
              "replacement":{"subject":{"id":"k1"},"predicate":"LICENSED_UNDER","object":{"ref":"g1"},
               "statement":"GraphSpindle is licensed under GPL-3.0.","evidence":"GraphSpindle is GPL-3.0 licensed","confidence":0.9}}]}
            """;

    @Test
    void supersedeWithoutCorrectionLanguageBecomesFlaggedConflict() {
        ParsedProposal p = ProposalTransforms.downgradeUnsupportedCorrections(parser.parse(SUPERSEDE), cues, GPL);
        assertThat(p.proposal().operations().get(1)).isInstanceOf(FlagConflict.class);
        ValidatedPlan plan = validator.validate(p, context(GPL));
        assertThat(plan.rejected()).isEmpty();
        assertThat(plan.commands(PlanCommand.SupersedeClaimCommand.class)).isEmpty();
        assertThat(plan.commands(PlanCommand.FlagConflictCommand.class)).singleElement()
                .satisfies(f -> assertThat(f.claimId()).isEqualTo("id-c1"));
        assertThat(plan.adjusted()).extracting(Adjustment::kind).contains(Adjustment.Kind.CORRECTION_DOWNGRADED);
    }

    @Test
    void supersedeWithCorrectionLanguageStays() {
        String text = "Correction: GraphSpindle is GPL-3.0 licensed.";
        ParsedProposal p = ProposalTransforms.downgradeUnsupportedCorrections(parser.parse(SUPERSEDE), cues, text);
        ValidatedPlan plan = validator.validate(p, context(text));
        assertThat(plan.commands(PlanCommand.SupersedeClaimCommand.class)).hasSize(1);
        assertThat(plan.adjusted()).extracting(Adjustment::kind).doesNotContain(Adjustment.Kind.CORRECTION_DOWNGRADED);
    }

    // ------------------------------------------------------------------ decisions are immutable

    @Test
    void supersedingADecisionsChoiceIsRejected() {
        String text = "Change of plan: we're switching GraphSpindle from Apache 2.0 to the MIT license.";
        String raw = """
                {"operations":[
                 {"op":"create_entity","ref":"s1","type":"Standard","name":"MIT License","aliases":["MIT"]},
                 {"op":"supersede_claim","claimId":"c2","explicitCorrection":true,"reason":"switch",
                  "replacement":{"subject":{"id":"k3"},"predicate":"CHOOSES","object":{"ref":"s1"},
                   "statement":"The decision chooses MIT.","evidence":"switching GraphSpindle from Apache 2.0 to the MIT license","confidence":0.9}}]}
                """;
        ParsedProposal p = ProposalTransforms.downgradeUnsupportedCorrections(parser.parse(raw), cues, text);
        ValidatedPlan plan = validator.validate(p, context(text));
        assertThat(plan.rejectedCodes()).containsExactly(RejectionCode.DECISION_IMMUTABLE);
    }

    // ------------------------------------------------------------------ attribute fields on create_entity

    @Test
    void attributeFieldOnCreateIsDroppedNotTheEntity() {
        String text = "I am building GraphSpindle in Java.";
        String raw = """
                {"operations":[
                 {"op":"create_entity","ref":"e1","type":"Technology","name":"Java","category":"LANGUAGE"},
                 {"op":"assert_claim","subject":{"id":"k1"},"predicate":"WRITTEN_IN","object":{"ref":"e1"},
                  "statement":"GraphSpindle is written in Java.","evidence":"building GraphSpindle in Java","confidence":0.9}]}
                """;
        ParsedProposal p = parser.parse(raw, CORE.attributeNames());
        assertThat(p.rejected()).isEmpty();
        assertThat(p.unknownFields()).contains("operations[0].category");
        ValidatedPlan plan = validator.validate(p, context(text));
        assertThat(plan.rejected()).isEmpty();
        assertThat(plan.commands(PlanCommand.CreateEntityCommand.class)).hasSize(1);
        assertThat(plan.adjusted()).extracting(Adjustment::kind).contains(Adjustment.Kind.ATTRIBUTE_FIELD_DROPPED);
    }

    @Test
    void foreignFieldStillRejectsAndDependentsReportDependencyRejected() {
        String text = "I am building GraphSpindle in Java.";
        String raw = """
                {"operations":[
                 {"op":"create_entity","ref":"e1","type":"Technology","name":"Java","brainId":"other"},
                 {"op":"assert_claim","subject":{"id":"k1"},"predicate":"WRITTEN_IN","object":{"ref":"e1"},
                  "statement":"GraphSpindle is written in Java.","evidence":"building GraphSpindle in Java","confidence":0.9}]}
                """;
        ValidatedPlan plan = validator.validate(parser.parse(raw, CORE.attributeNames()), context(text));
        assertThat(plan.rejected()).extracting(r -> r.opIndex() + ":" + r.code())
                .containsExactly("0:UNKNOWN_FIELD", "1:DEPENDENCY_REJECTED");
    }

    // ------------------------------------------------------------------ parser error position

    @Test
    void malformedMessageCarriesThePosition() {
        String raw = "{\"summary\":\"x\",\n \"operations\":[{\"op\":\"add_alias\",\"target\":{\"id\":\"k1\"} \"alias\":\"GS\"}]}";
        assertThatThrownBy(() -> parser.parse(raw)).isInstanceOf(MalformedModelOutputException.class)
                .hasMessageContaining("line 2 column").hasMessageContaining("text before the error");
        assertThatThrownBy(() -> parser.parse("no json here")).hasMessageContaining("contains no '{'");
    }

    // ------------------------------------------------------------------ named speaker

    @Test
    void newSpeakerIsCreatedByTheEngineAndFirstPersonEvidenceNamesIt() {
        String text = "I maintain GraphSpindle.";
        String raw = """
                {"operations":[{"op":"assert_claim","subject":{"id":"P0"},"predicate":"WORKS_ON","object":{"id":"k1"},
                  "statement":"ci-bot maintains GraphSpindle.","evidence":"I maintain GraphSpindle","confidence":0.9}]}
                """;
        ParsedProposal p = ProposalTransforms.createSpeaker(parser.parse(raw), "Agent", "ci-bot");
        CandidateSet set = CandidateSet.builder().entity(project()).build();
        ValidatedPlan plan = validator.validate(p, ValidationContext.of("dev", CORE, set, text, List.of()));
        assertThat(plan.rejected()).isEmpty();
        assertThat(plan.commands(PlanCommand.CreateEntityCommand.class)).singleElement()
                .satisfies(c -> assertThat(c.name()).isEqualTo("ci-bot"));
        assertThat(plan.adjusted()).extracting(Adjustment::kind).contains(Adjustment.Kind.SPEAKER_CREATED);
        assertThat(p.wireOperationCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    static EntityCandidate project() {
        return new EntityCandidate("k1", "id-k1", "Project", "GraphSpindle", "graphspindle", "project:graphspindle",
                Set.of("graphspindle"), false, Set.of("Project"));
    }

    static ValidationContext context(String content) {
        CandidateSet set = CandidateSet.builder()
                .entity(new EntityCandidate("P0", "id-P0", "Person", "Me", "@self", "person:@self", Set.of(), false,
                        Set.of()))
                .entity(project())
                .entity(new EntityCandidate("k2", "id-k2", "Standard", "MIT License", Normalizer.norm("MIT License"),
                        "standard:mit license", Set.of("mit license", "mit"), false, Set.of()))
                .entity(new EntityCandidate("k3", "id-k3", "Decision", "Use Apache License 2.0 for GraphSpindle",
                        Normalizer.norm("Use Apache License 2.0 for GraphSpindle"),
                        "decision:" + Normalizer.norm("Use Apache License 2.0 for GraphSpindle"), Set.of(), false,
                        Set.of()))
                .claim(new ClaimCandidate("c1", "id-c1", "id-k1", "LICENSED_UNDER", ClaimKind.RELATION, "id-k2", null,
                        null, Polarity.POSITIVE, Origin.LLM))
                .claim(new ClaimCandidate("c2", "id-c2", "id-k3", "CHOOSES", ClaimKind.RELATION, "id-k2", null, null,
                        Polarity.POSITIVE, Origin.LLM))
                .build();
        return ValidationContext.of("dev", CORE, set, content, List.of());
    }
}
