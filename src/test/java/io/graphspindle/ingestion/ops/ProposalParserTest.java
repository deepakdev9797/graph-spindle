package io.graphspindle.ingestion.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.graphspindle.common.Limits;
import io.graphspindle.common.Polarity;
import io.graphspindle.ingestion.validation.RejectionCode;
import io.graphspindle.ingestion.validation.ValidationIssue;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProposalParserTest {

    private static final String ONE_OP = """
            {"summary":"s","operations":[{"op":"create_entity","ref":"e1","type":"Project","name":"GraphSpindle"}]}""";

    private final ProposalParser parser = new ProposalParser();

    static String resource(String name) {
        try (InputStream in = ProposalParserTest.class.getResourceAsStream("/proposals/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void parsesTheReadmeExample() {
        ParsedProposal p = parser.parse(resource("readme-example.json"));
        assertThat(p.rejected()).isEmpty();
        assertThat(p.unknownFields()).isEmpty();
        assertThat(p.proposal().summary()).startsWith("Speaker is building");
        assertThat(p.proposal().operations()).hasSize(19);
        assertThat(p.proposal().operations()).filteredOn(CreateEntity.class::isInstance).hasSize(6);
        assertThat(p.proposal().operations()).filteredOn(AssertClaim.class::isInstance).hasSize(13);
        CreateEntity apache = (CreateEntity) p.proposal().operations().get(4);
        assertThat(apache.aliases()).containsExactly("Apache 2.0", "Apache-2.0");
        AssertClaim first = (AssertClaim) p.proposal().operations().get(6);
        assertThat(first.subject()).isEqualTo(EntityTarget.handle("P0"));
        assertThat(first.object()).isEqualTo(EntityTarget.ref("e1"));
        assertThat(first.polarity()).isEqualTo(Polarity.POSITIVE);
        assertThat(first.confidence()).isEqualTo(0.95);
        AssertClaim category = (AssertClaim) p.proposal().operations().get(10);
        assertThat(category.value()).isEqualTo("LANGUAGE");
        assertThat(category.isRelation()).isFalse();
    }

    @Test
    void parsesUpdateAndCorrectionExamples() {
        ParsedProposal update = parser.parse(resource("update-example.json"));
        assertThat(update.rejected()).isEmpty();
        assertThat(update.proposal().operations().get(8)).isEqualTo(new LinkSource(0, EntityTarget.handle("e1")));
        ParsedProposal correction = parser.parse(resource("correction-example.json"));
        assertThat(correction.rejected()).isEmpty();
        SupersedeClaim s = (SupersedeClaim) correction.proposal().operations().get(7);
        assertThat(s.claimHandle()).isEqualTo("c13");
        assertThat(s.explicitCorrection()).isTrue();
        assertThat(s.replacement().object()).isEqualTo(EntityTarget.ref("s1"));
    }

    @Test
    void acceptsCodeFences() {
        assertThat(parser.parse("```json\n" + ONE_OP + "\n```").proposal().operations()).hasSize(1);
        assertThat(parser.parse("```\n" + ONE_OP + "\n```").proposal().operations()).hasSize(1);
        // missing closing fence (truncated after the JSON)
        assertThat(parser.parse("```json\n" + ONE_OP).proposal().operations()).hasSize(1);
    }

    @Test
    void acceptsLeadingAndTrailingProse() {
        String raw = "Sure! Here is the proposal you asked for:\n" + ONE_OP
                + "\nLet me know if you want {more} changes. }}}";
        assertThat(parser.parse(raw).proposal().operations()).hasSize(1);
    }

    @Test
    void skipsBracesInLeadingProse() {
        String raw = "I considered {\"x\": 1} and {broken but then: " + ONE_OP;
        assertThat(parser.parse(raw).proposal().operations()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "hello", "Sorry, I cannot help with that.", "[1,2,3]", "{}",
            "{\"summary\":\"no ops\"}", "{\"operations\": {}}", "{\"operations\": \"none\"}", "null", "```json\n```"})
    void rejectsNonProposals(String raw) {
        assertThatThrownBy(() -> parser.parse(raw)).isInstanceOf(MalformedModelOutputException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(MalformedModelOutputException.class);
    }

    @Test
    void rejectsTruncatedJson() {
        String truncated = ONE_OP.substring(0, ONE_OP.length() - 10);
        assertThatThrownBy(() -> parser.parse(truncated)).isInstanceOf(MalformedModelOutputException.class);
        String readme = resource("readme-example.json");
        assertThatThrownBy(() -> parser.parse(readme.substring(0, readme.length() / 2)))
                .isInstanceOf(MalformedModelOutputException.class);
    }

    @Test
    void rejectsDuplicateKeysAsAmbiguous() {
        String raw = "{\"operations\":[{\"op\":\"create_entity\",\"ref\":\"e1\",\"type\":\"Project\",\"type\":\"Admin\","
                + "\"name\":\"X\"}]}";
        assertThatThrownBy(() -> parser.parse(raw)).isInstanceOf(MalformedModelOutputException.class);
    }

    @Test
    void unknownOpIsRejectedPerOperation() {
        String raw = """
                {"operations":[
                  {"op":"delete_all_nodes"},
                  {"op":"run_cypher","query":"MATCH (n) DETACH DELETE n"},
                  {"name":"no op"},
                  {"op":42},
                  "create_entity",
                  {"op":"create_entity","ref":"e1","type":"Project","name":"GraphSpindle"}
                ]}""";
        ParsedProposal p = parser.parse(raw);
        assertThat(p.proposal().operations()).hasSize(1);
        assertThat(p.wireIndexes()).containsExactly(5);
        assertThat(p.rejected()).extracting(ValidationIssue::code).containsExactly(RejectionCode.UNKNOWN_OP,
                RejectionCode.UNKNOWN_OP, RejectionCode.UNKNOWN_OP, RejectionCode.UNKNOWN_OP,
                RejectionCode.INVALID_VALUE);
        assertThat(p.rejected()).extracting(ValidationIssue::opIndex).containsExactly(0, 1, 2, 3, 4);
        assertThat(p.wireOperationCount()).isEqualTo(6);
    }

    @Test
    void opNameIsCaseAndWhitespaceTolerant() {
        String raw = "{\"operations\":[{\"op\":\" Create_Entity \",\"ref\":\"e1\",\"type\":\"Project\",\"name\":\"X\"}]}";
        assertThat(parser.parse(raw).proposal().operations()).hasSize(1);
    }

    @Test
    void unknownFieldsAreRecordedAndRejectTheOperation() {
        String raw = """
                {"summary":"s","brainId":"other-brain","cypher":"MATCH (n) RETURN n","operations":[
                  {"op":"create_entity","ref":"e1","type":"Project","name":"X","brainId":"brain-b"},
                  {"op":"assert_claim","subject":{"id":"k1","brainId":"b"},"predicate":"USES","object":{"ref":"e1"},
                   "statement":"s","evidence":"e","confidence":0.9},
                  {"op":"supersede_claim","claimId":"c1","explicitCorrection":true,"reason":"r",
                   "replacement":{"subject":{"id":"k1"},"predicate":"USES","object":{"id":"k2"},"statement":"s",
                   "evidence":"e","confidence":0.9,"label":"Admin"}},
                  {"op":"create_entity","ref":"e2","type":"Project","name":"Y"}
                ]}""";
        ParsedProposal p = parser.parse(raw);
        assertThat(p.unknownFields()).containsExactly("brainId", "cypher", "operations[0].brainId",
                "operations[1].subject.brainId", "operations[2].replacement.label");
        assertThat(p.rejected()).extracting(ValidationIssue::code).containsOnly(RejectionCode.UNKNOWN_FIELD);
        assertThat(p.rejected()).hasSize(3);
        assertThat(p.proposal().operations()).hasSize(1);
        assertThat(p.proposal().summary()).isEqualTo("s");
    }

    @Test
    void structuralErrorsAreInvalidValue() {
        String raw = """
                {"operations":[
                  {"op":"create_entity","ref":"e1","type":"Project"},
                  {"op":"create_entity","ref":"e1","type":"Project","name":7},
                  {"op":"assert_claim","subject":{"ref":"e1","id":"k1"},"predicate":"USES","object":{"id":"k2"},
                   "statement":"s","evidence":"e","confidence":0.9},
                  {"op":"assert_claim","subject":{"id":"k1"},"predicate":"USES","object":{"id":"k2"},"value":"x",
                   "statement":"s","evidence":"e","confidence":0.9},
                  {"op":"assert_claim","subject":{"id":"k1"},"predicate":"USES",
                   "statement":"s","evidence":"e","confidence":0.9},
                  {"op":"assert_claim","subject":"k1","predicate":"USES","object":{"id":"k2"},
                   "statement":"s","evidence":"e","confidence":0.9},
                  {"op":"assert_claim","subject":{"id":"k1"},"predicate":"USES","object":{"id":"k2"},
                   "statement":"s","evidence":"e","confidence":"high"},
                  {"op":"assert_claim","subject":{"id":"k1"},"predicate":"USES","object":{"id":"k2"},
                   "statement":"s","evidence":"e","confidence":0.9,"polarity":"MAYBE"},
                  {"op":"link_source","source":"0","target":{"id":"k1"}},
                  {"op":"supersede_claim","claimId":"c1","explicitCorrection":"perhaps","reason":"r",
                   "replacement":{"subject":{"id":"k1"},"predicate":"USES","object":{"id":"k2"},"statement":"s",
                   "evidence":"e","confidence":0.9}},
                  {"op":"flag_conflict","claimId":"c1","incoming":{"op":"retract_claim"},"description":"d"},
                  {"op":"create_entity","ref":"e1","type":"Project","name":"X","aliases":"X"}
                ]}""";
        ParsedProposal p = parser.parse(raw);
        assertThat(p.proposal().operations()).isEmpty();
        assertThat(p.rejected()).hasSize(12).extracting(ValidationIssue::code).containsOnly(RejectionCode.INVALID_VALUE);
    }

    @Test
    void lenientScalarsAreAccepted() {
        String raw = """
                {"operations":[
                  {"op":"assert_claim","subject":{"id":"k1"},"predicate":"version","value":21,
                   "statement":"s","evidence":"e","confidence":"0.8","polarity":"negative","sources":[0,0,1]},
                  {"op":"supersede_claim","claimId":"c1","explicitCorrection":"TRUE","reason":"r",
                   "replacement":{"op":"assert_claim","subject":{"id":"k1"},"predicate":"USES","object":{"id":"k2"},
                   "statement":"s","evidence":"e","confidence":1}},
                  {"op":"create_entity","ref":"e1","type":"Project","name":"X","description":null,"aliases":null}
                ]}""";
        ParsedProposal p = parser.parse(raw);
        assertThat(p.rejected()).isEmpty();
        AssertClaim a = (AssertClaim) p.proposal().operations().get(0);
        assertThat(a.value()).isEqualTo("21");
        assertThat(a.confidence()).isEqualTo(0.8);
        assertThat(a.polarity()).isEqualTo(Polarity.NEGATIVE);
        assertThat(a.sources()).containsExactly(0, 0, 1);
        assertThat(((SupersedeClaim) p.proposal().operations().get(1)).explicitCorrection()).isTrue();
        assertThat(((CreateEntity) p.proposal().operations().get(2)).aliases()).isEmpty();
    }

    @Test
    void refusesOversizedOutputBeforeParsing() {
        ProposalParser small = new ProposalParser(new Limits(32_000, 10, 60, 25, 5, 30, 1000, 500));
        assertThatThrownBy(() -> small.parse(ONE_OP + " ".repeat(1000)))
                .isInstanceOf(MalformedModelOutputException.class).hasMessageContaining("exceeds");
    }

    @Test
    void refusesTooManyOperations() {
        StringBuilder sb = new StringBuilder("{\"operations\":[");
        for (int i = 0; i < 501; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"op\":\"x\"}");
        }
        String raw = sb.append("]}").toString();
        assertThatThrownBy(() -> parser.parse(raw)).isInstanceOf(MalformedModelOutputException.class)
                .hasMessageContaining("501");
    }

    @Test
    void handlesOneMegabyteGarbageQuickly() {
        String garbage = "a(b[c{".repeat(180_000);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> parser.parse(garbage)).isInstanceOf(MalformedModelOutputException.class));
        String braces = "{".repeat(1_000_000);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> parser.parse(braces)).isInstanceOf(MalformedModelOutputException.class));
        String fences = "```\n".repeat(250_000);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> parser.parse(fences)).isInstanceOf(MalformedModelOutputException.class));
    }

    @Test
    void handlesOneMegabyteStringValueQuickly() {
        String huge = "x".repeat(1_000_000);
        String raw = "{\"operations\":[{\"op\":\"create_entity\",\"ref\":\"e1\",\"type\":\"Project\",\"name\":\""
                + huge + "\"},{\"op\":\"create_entity\",\"ref\":\"e2\",\"type\":\"Project\",\"name\":\"ok\"}]}";
        ParsedProposal p = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> parser.parse(raw));
        // the parser keeps the op; the validator rejects the oversized name (INVALID_VALUE)
        assertThat(p.proposal().operations()).hasSize(2);
    }

    @Test
    void deepNestingIsRefused() {
        String deep = "{\"operations\":[" + "[".repeat(5000) + "]".repeat(5000) + "]}";
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> parser.parse(deep)).isInstanceOf(MalformedModelOutputException.class));
    }

    @Test
    void nonStringSummaryIsIgnoredAndRecorded() {
        ParsedProposal p = parser.parse("{\"summary\":{\"x\":1},\"operations\":[]}");
        assertThat(p.proposal().summary()).isNull();
        assertThat(p.unknownFields()).contains("summary(not a string)");
    }
}
