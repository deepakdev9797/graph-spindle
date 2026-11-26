package io.graphspindle.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class VocabularyRegistryTest {

    private final VocabularyRegistry registry = new VocabularyRegistry();

    @Test
    void coreHasEverything() {
        BrainVocabulary core = registry.core();
        assertThat(core.profile()).isEqualTo("core-v1");
        assertThat(core.entityTypes()).hasSize(15);
        assertThat(core.relationPredicates()).hasSize(28);
        assertThat(core.attributePredicates()).hasSize(28);
        assertThat(core.predicateCount()).isEqualTo(28 + 21);
        assertThat(registry.forBrain(null)).isSameAs(core);
    }

    @Test
    void subsetRemovesTypesAndRestrictsOrDropsPredicates() {
        BrainVocabulary v = registry.forBrain(VocabularyConfigValidatorTest.schemaExample());
        assertThat(v.entityTypeNames()).containsExactly("Person", "Project", "Technology", "Standard", "Concept",
                "Decision", "Task", "Recipe");
        assertThat(v.isEnabledType("Place")).isFalse();
        assertThat(v.relation("RELATED_TO")).isEmpty();
        // HAS_PREFERENCE needs Preference, which is disabled: predicate dropped entirely
        assertThat(v.relation("HAS_PREFERENCE")).isEmpty();
        // LOCATED_IN needs Place targets: dropped
        assertThat(v.relation("LOCATED_IN")).isEmpty();
        // CHOOSES keeps only enabled targets
        assertThat(v.relation("CHOOSES").orElseThrow().targetTypes().types())
                .containsExactlyInAnyOrder("Technology", "Standard", "Concept", "Person", "Project");
        assertThat(v.allowsRelation("WORKS_ON", "Person", "Project")).isTrue();
        assertThat(v.allowsRelation("WORKS_ON", "Agent", "Project")).isFalse();
        // PART_OF: Task -> Project extra survives, Artifact one does not
        assertThat(v.relation("PART_OF").orElseThrow().extraTargetsBySource()).containsOnlyKeys("Task");
        // attributes restricted to enabled types
        assertThat(v.attribute("category", "Place")).isEmpty();
        assertThat(v.attribute("strength", "Preference")).isEmpty();
        assertThat(v.hasAttributeName("strength")).isFalse();
    }

    @Test
    void overridesCardinality() {
        BrainVocabulary v = registry.forBrain(VocabularyConfigValidatorTest.schemaExample());
        assertThat(v.relation("LICENSED_UNDER").orElseThrow().cardinality()).isEqualTo(Cardinality.MANY);
        assertThat(registry.core().relation("LICENSED_UNDER").orElseThrow().cardinality()).isEqualTo(Cardinality.ONE);
    }

    @Test
    void addsCustomTypesAndPredicates() {
        BrainVocabulary v = registry.forBrain(VocabularyConfigValidatorTest.schemaExample());
        assertThat(v.entityType("Recipe").orElseThrow().core()).isFalse();
        assertThat(v.allowsRelation("INGREDIENT_OF", "Concept", "Recipe")).isTrue();
        assertThat(v.allowsRelation("INGREDIENT_OF", "Recipe", "Concept")).isFalse();
        assertThat(v.attribute("cuisine", "Recipe").orElseThrow().maxLength()).isEqualTo(50);
        assertThat(v.attribute("difficulty", "Recipe").orElseThrow().enumValues())
                .containsExactly("EASY", "MEDIUM", "HARD");
        AttributePredicateDef servings = v.attribute("servings", "Recipe").orElseThrow();
        assertThat(servings.valueType()).isEqualTo(ValueType.NUMBER);
        assertThat(servings.min()).isEqualByComparingTo("1");
        assertThat(v.compatible("Recipe", "Concept")).isFalse();
    }

    @Test
    void customTypeCanJoinACompatibilityGroup() {
        BrainVocabulary v = registry.forBrain(new VocabularyConfig(null, null, null, null, List.of(
                new VocabularyConfig.CustomEntityType("Library", "a software library", "TECHNICAL", List.of())),
                null, null));
        assertThat(v.compatible("Library", "Technology")).isTrue();
        assertThat(v.compatible("Library", "Project")).isFalse();
    }

    @Test
    void invalidConfigThrowsWithIssues() {
        assertThatThrownBy(() -> registry.forBrain(new VocabularyConfig(null, List.of("Project"), null, null, null,
                null, null)))
                .isInstanceOfSatisfying(InvalidVocabularyConfigException.class,
                        e -> assertThat(e.issues()).extracting(VocabularyIssue::code)
                                .containsExactly(VocabularyIssue.Code.CANNOT_DISABLE));
    }

    @Test
    void disabledPredicateIsGone() {
        BrainVocabulary v = registry.forBrain(new VocabularyConfig(null, null, List.of("RELATED_TO", "status"), null,
                null, null, null));
        assertThat(v.relation("RELATED_TO")).isEmpty();
        assertThat(v.hasAttributeName("status")).isFalse();
        assertThat(v.relationPredicates()).hasSize(27);
    }
}
