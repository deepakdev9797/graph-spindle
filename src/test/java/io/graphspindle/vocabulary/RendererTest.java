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
                "supersede_claim", "retract_claim", "flag_conflict", "propose_merge", "link_source");

        JsonNode create = ops.get("items").get("oneOf").get(0);
        List<String> types = new ArrayList<>();
        create.get("properties").get("type").get("enum").forEach(t -> types.add(t.stringValue()));
        assertThat(types).containsExactlyElementsOf(core.entityTypeNames());

        JsonNode assertClaim = ops.get("items").get("oneOf").get(3);
        List<String> predicates = new ArrayList<>();
        assertClaim.get("properties").get("predicate").get("enum").forEach(p -> predicates.add(p.stringValue()));
        assertThat(predicates).hasSize(28 + 13).contains("USES", "status", "homepage");
        assertThat(assertClaim.get("properties").get("evidence").get("maxLength").intValue()).isEqualTo(300);
        // value must admit the longest attribute literal (homepage URLs up to 2000), not 500
        assertThat(assertClaim.get("properties").get("value").get("maxLength").intValue())
                .isEqualTo(AttributeValues.MAX_VALUE_LENGTH);
        assertThat(assertClaim.get("oneOf")).hasSize(2);
        JsonNode supersede = ops.get("items").get("oneOf").get(4);
        assertThat(supersede.get("properties").get("replacement").get("properties").has("op")).isFalse();
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
