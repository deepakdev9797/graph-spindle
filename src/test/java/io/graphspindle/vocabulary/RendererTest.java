package io.graphspindle.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.common.Limits;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RendererTest {

    private final VocabularyRegistry registry = new VocabularyRegistry();
    private final PromptVocabularyRenderer prompt = new PromptVocabularyRenderer();
    private final OutputSchemaRenderer schema = new OutputSchemaRenderer();

    @Test
    void promptListsEveryTypeAndPredicateOfTheBrain() {
        BrainVocabulary core = registry.core();
        String text = prompt.render(core);
        core.entityTypes().forEach(t -> assertThat(text).contains("- " + t.name() + ": " + t.description()));
        core.relationPredicates().forEach(p -> assertThat(text).contains("- " + p.name() + ": "));
        core.attributeNames().forEach(a -> assertThat(text).contains("- " + a + " on "));
        CoreVocabulary.CLASSIFICATION_HINTS.forEach(h -> assertThat(text).contains(h));
        assertThat(text).contains("PART_OF: is a component or sub-part of. Artifact, Concept")
                .contains("the same type as the subject")
                .contains("Project when the subject is Task")
                .contains("RELATED_TO").contains("confidence capped at 0.6");
    }

    @Test
    void promptRendersSynonymsCompactlyAndStaysWithinBudget() {
        String text = prompt.render(registry.core());
        assertThat(text).contains("- Person: A specific human being (aka human, individual, contact, colleague)")
                .contains("aka developer, engineer")
                .contains("MEMBER_OF: belongs to, or is employed by.").contains("aka works at, employed by")
                .contains("- email on Person: email address.");
        // ADR-025: the richer YAML defaults keep the vocabulary section compact
        assertThat(text.length()).isLessThan(10_000);
    }

    @Test
    void promptNeverOffersEngineOnlyValues() {
        String text = prompt.render(registry.core());
        String decisionStatus = text.lines().filter(l -> l.startsWith("- status on Decision")).findFirst().orElseThrow();
        assertThat(decisionStatus).contains("PROPOSED, ACCEPTED, REVERSED").doesNotContain("SUPERSEDED");
    }

    @Test
    void promptIsDeterministicAndReflectsTheBrainSubset() {
        BrainVocabulary v = registry.forBrain(VocabularyConfigValidatorTest.schemaExample());
        assertThat(prompt.render(v)).isEqualTo(prompt.render(registry.forBrain(VocabularyConfigValidatorTest
                .schemaExample())));
        String text = prompt.render(v);
        assertThat(text).contains("- Recipe: A cooking recipe").contains("INGREDIENT_OF")
                .contains("servings on Recipe").contains("between 1 and 100")
                .doesNotContain("- Place:").doesNotContain("RELATED_TO").doesNotContain("HAS_PREFERENCE")
                .doesNotContain("An Artifact exists only");
        assertThat(text).contains("LICENSED_UNDER: distributed under the license. Project, Technology -> Standard; MANY");
    }

    @Test
    void schemaIsValidJsonWithVocabularyEnums() {
        BrainVocabulary core = registry.core();
        Limits limits = Limits.defaults();
        String text = schema.render(core, limits);
        JsonNode root = JsonMapper.builder().build().readTree(text);
        assertThat(root.get("required").get(0).stringValue()).isEqualTo("operations");
        assertThat(root.get("additionalProperties").booleanValue()).isFalse();
        JsonNode ops = root.get("properties").get("operations");
        assertThat(ops.get("maxItems").intValue()).isEqualTo(60);
        List<String> opNames = new ArrayList<>();
        ops.get("items").get("oneOf").forEach(o -> opNames.add(o.get("properties").get("op").get("const").stringValue()));
        assertThat(opNames).containsExactly("create_entity", "update_entity", "add_alias", "assert_claim",
                "assert_claim", "supersede_claim", "retract_claim", "flag_conflict", "propose_merge", "link_source");

        JsonNode create = ops.get("items").get("oneOf").get(0);
        List<String> types = new ArrayList<>();
        create.get("properties").get("type").get("enum").forEach(t -> types.add(t.stringValue()));
        assertThat(types).containsExactlyElementsOf(core.entityTypeNames());

        // assert_claim: a relation alternative (object required) and an attribute alternative (value required)
        JsonNode relationClaim = ops.get("items").get("oneOf").get(3);
        JsonNode attributeClaim = ops.get("items").get("oneOf").get(4);
        List<String> relations = new ArrayList<>();
        relationClaim.get("properties").get("predicate").get("enum").forEach(p -> relations.add(p.stringValue()));
        List<String> attributes = new ArrayList<>();
        attributeClaim.get("properties").get("predicate").get("enum").forEach(p -> attributes.add(p.stringValue()));
        assertThat(relations).hasSize(28).contains("USES").doesNotContain("status");
        assertThat(attributes).hasSize(21).contains("status", "homepage", "email").doesNotContain("USES");
        assertThat(relationClaim.get("required").toString()).contains("\"object\"", "\"statement\"", "\"op\"");
        assertThat(relationClaim.get("properties").has("value")).isFalse();
        assertThat(attributeClaim.get("required").toString()).contains("\"value\"", "\"statement\"");
        assertThat(attributeClaim.get("properties").has("object")).isFalse();
        assertThat(relationClaim.get("properties").get("evidence").get("maxLength").intValue()).isEqualTo(300);
        // value must admit the longest attribute literal (homepage URLs up to 2000), not 500
        assertThat(attributeClaim.get("properties").get("value").get("maxLength").intValue())
                .isEqualTo(AttributeValues.MAX_VALUE_LENGTH);
        JsonNode supersede = ops.get("items").get("oneOf").get(5);
        assertThat(supersede.get("properties").get("replacement").toString())
                .contains("#/$defs/relationClaim", "#/$defs/attributeClaim");
        assertThat(root.get("$defs").get("relationClaim").get("properties").has("op")).isFalse();

        // every operation alternative is a closed object, and no schema mixes "oneOf" with sibling
        // properties/required (grammar converters read that as "any value", ADR-030)
        ops.get("items").get("oneOf").forEach(o -> {
            assertThat(o.get("type").stringValue()).isEqualTo("object");
            assertThat(o.get("additionalProperties").booleanValue()).isFalse();
        });
        assertNoMixedOneOf(root);
    }

    private static void assertNoMixedOneOf(JsonNode n) {
        if (n.isObject()) {
            if (n.has("oneOf")) {
                assertThat(n.has("properties") || n.has("required") || n.has("type")).as(n.toString()).isFalse();
                n.get("oneOf").forEach(alt -> assertThat(alt.has("type") || alt.has("$ref")).as(alt.toString())
                        .isTrue());
            }
            n.forEach(RendererTest::assertNoMixedOneOf);
        } else if (n.isArray()) {
            n.forEach(RendererTest::assertNoMixedOneOf);
        }
    }

    @Test
    void schemaFollowsTheBrainSubset() {
        JsonNode root = schema.renderNode(registry.forBrain(VocabularyConfigValidatorTest.schemaExample()),
                Limits.defaults().withMaxOperations(10));
        assertThat(root.toString()).contains("\"Recipe\"").contains("\"INGREDIENT_OF\"")
                .doesNotContain("\"RELATED_TO\"").doesNotContain("\"Place\"");
        assertThat(root.get("properties").get("operations").get("maxItems").intValue()).isEqualTo(10);
    }
}
