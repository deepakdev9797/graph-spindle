package io.graphspindle.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CoreVocabularyTest {

    private static final Set<String> TYPES =
            CoreVocabulary.ENTITY_TYPES.stream().map(EntityTypeDef::name).collect(Collectors.toSet());

    @Test
    void countsMatchSchemaSection6() {
        assertThat(CoreVocabulary.ENTITY_TYPES).hasSize(15);
        assertThat(CoreVocabulary.RELATION_PREDICATES).hasSize(28);
        assertThat(CoreVocabulary.ATTRIBUTE_PREDICATES).hasSize(20);
        assertThat(CoreVocabulary.attributeNames()).containsExactlyInAnyOrder("status", "category", "role", "version",
                "homepage", "rationale", "decidedOn", "dueDate", "startDate", "endDate", "priority", "strength",
                "detail");
    }

    @Test
    void entityTypesAreExactlyTheCoreFifteen() {
        assertThat(TYPES).containsExactlyInAnyOrder("Person", "Agent", "Organization", "Project", "Technology",
                "Standard", "Artifact", "Concept", "Decision", "Task", "Goal", "Event", "Place", "Preference", "Skill");
    }

    @Test
    void relationPredicatesAreExactlyTheCoreTwentyEight() {
        assertThat(CoreVocabulary.RELATION_PREDICATES).extracting(RelationPredicateDef::name).containsExactly(
                "WORKS_ON", "MEMBER_OF", "CREATED", "MAINTAINS", "USES", "WRITTEN_IN", "DEPENDS_ON", "PART_OF",
                "LICENSED_UNDER", "IMPLEMENTS", "ALTERNATIVE_TO", "IS_A", "ABOUT", "DECIDED", "APPLIES_TO", "CHOOSES",
                "REJECTS", "REPLACES", "ASSIGNED_TO", "BLOCKS", "CONTRIBUTES_TO", "PARTICIPATED_IN", "LOCATED_IN",
                "KNOWS", "HAS_PREFERENCE", "HAS_SKILL", "INTERESTED_IN", "RELATED_TO");
    }

    @Test
    void everyPredicatesSourceAndTargetTypesExist() {
        for (RelationPredicateDef p : CoreVocabulary.RELATION_PREDICATES) {
            assertThat(TYPES).as(p.name() + " sources").containsAll(p.sourceTypes().types());
            assertThat(TYPES).as(p.name() + " targets").containsAll(p.targetTypes().types());
            p.extraTargetsBySource().forEach((src, targets) -> {
                assertThat(TYPES).contains(src);
                assertThat(TYPES).containsAll(targets);
            });
            assertThat(p.isUsable()).as(p.name()).isTrue();
            assertThat(p.core()).isTrue();
        }
        for (AttributePredicateDef a : CoreVocabulary.ATTRIBUTE_PREDICATES) {
            assertThat(TYPES).as(a.name()).containsAll(a.entityTypes());
            assertThat(a.entityTypes()).isNotEmpty();
        }
    }

    @Test
    void noReservedNamesAreUsed() {
        for (EntityTypeDef t : CoreVocabulary.ENTITY_TYPES) {
            assertThat(CoreVocabulary.isReservedLabel(t.name())).as(t.name()).isFalse();
        }
        for (RelationPredicateDef p : CoreVocabulary.RELATION_PREDICATES) {
            assertThat(CoreVocabulary.SYSTEM_RELATIONSHIP_TYPES).doesNotContain(p.name());
            assertThat(CoreVocabulary.isReservedLabel(p.name())).isFalse();
        }
        assertThat(CoreVocabulary.isReservedLabel("Entity")).isTrue();
        assertThat(CoreVocabulary.isReservedLabel("apikey")).isTrue();
        assertThat(CoreVocabulary.isReservedLabel("_hidden")).isTrue();
        assertThat(CoreVocabulary.isReservedLabel("System")).isTrue();
        assertThat(CoreVocabulary.isReservedLabel("Internal")).isTrue();
    }

    @Test
    void namesFollowTheExtensibilityPatterns() {
        Pattern type = Pattern.compile("^[A-Z][A-Za-z0-9]{1,39}$");
        Pattern rel = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
        Pattern attr = Pattern.compile("^[a-z][A-Za-z0-9]{1,39}$");
        Pattern enumValue = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
        TYPES.forEach(t -> assertThat(t).matches(type));
        CoreVocabulary.RELATION_PREDICATES.forEach(p -> assertThat(p.name()).matches(rel));
        CoreVocabulary.ATTRIBUTE_PREDICATES.forEach(a -> {
            assertThat(a.name()).matches(attr);
            a.enumValues().forEach(v -> assertThat(v).matches(enumValue));
        });
    }

    @Test
    void sameAttributeNameNeverAppliesTwiceToOneType() {
        Set<String> seen = new HashSet<>();
        for (AttributePredicateDef a : CoreVocabulary.ATTRIBUTE_PREDICATES) {
            for (String t : a.entityTypes()) {
                assertThat(seen.add(a.name() + "@" + t)).as(a.name() + "@" + t).isTrue();
            }
        }
    }

    @Test
    void compatibilityGroupsMatchSchemaSection62() {
        assertThat(CoreVocabulary.COMPATIBILITY_GROUPS.values()).containsExactlyInAnyOrder(
                Set.of("Technology", "Standard", "Concept", "Skill"), Set.of("Project", "Artifact"),
                Set.of("Person", "Agent"), Set.of("Task", "Goal"));
        for (EntityTypeDef t : CoreVocabulary.ENTITY_TYPES) {
            if (t.compatibilityGroup() != null) {
                assertThat(CoreVocabulary.COMPATIBILITY_GROUPS.get(t.compatibilityGroup())).contains(t.name());
            }
        }
        BrainVocabulary v = new VocabularyRegistry().core();
        assertThat(v.compatible("Technology", "Skill")).isTrue();
        assertThat(v.compatible("Project", "Artifact")).isTrue();
        assertThat(v.compatible("Person", "Agent")).isTrue();
        assertThat(v.compatible("Task", "Goal")).isTrue();
        assertThat(v.compatible("Technology", "Place")).isFalse();
        assertThat(v.compatible("Decision", "Decision")).isTrue();
        assertThat(v.compatible("Decision", "Task")).isFalse();
    }

    @Test
    void spotChecksOfTypePairsAndCardinality() {
        BrainVocabulary v = new VocabularyRegistry().core();
        assertThat(v.allowsRelation("USES", "Project", "Technology")).isTrue();
        assertThat(v.allowsRelation("USES", "Concept", "Technology")).isFalse();
        assertThat(v.allowsRelation("PART_OF", "Task", "Task")).isTrue();
        assertThat(v.allowsRelation("PART_OF", "Task", "Project")).isTrue();
        assertThat(v.allowsRelation("PART_OF", "Artifact", "Project")).isTrue();
        assertThat(v.allowsRelation("PART_OF", "Event", "Project")).isFalse();
        assertThat(v.allowsRelation("PART_OF", "Person", "Person")).isFalse();
        assertThat(v.allowsRelation("ABOUT", "Event", "Preference")).isTrue();
        assertThat(v.allowsRelation("RELATED_TO", "Place", "Skill")).isTrue();
        assertThat(v.allowsRelation("REJECTS", "Decision", "Place")).isTrue();
        assertThat(v.relation("RELATED_TO").orElseThrow().confidenceCap()).isEqualTo(0.6);
        List<String> one = v.relationPredicates().stream().filter(p -> p.cardinality() == Cardinality.ONE)
                .map(RelationPredicateDef::name).toList();
        assertThat(one).containsExactlyInAnyOrder("LICENSED_UNDER", "CHOOSES", "REPLACES", "LOCATED_IN");
        assertThat(CoreVocabulary.ATTRIBUTE_PREDICATES).allMatch(a -> a.cardinality() == Cardinality.ONE);
    }

    @Test
    void decisionSupersededIsEngineOnly() {
        AttributePredicateDef status = new VocabularyRegistry().core().attribute("status", "Decision").orElseThrow();
        assertThat(status.enumValues()).contains("SUPERSEDED");
        assertThat(status.llmEnumValues()).containsExactly("PROPOSED", "ACCEPTED", "REVERSED");
        assertThat(status.engineOnlyValues()).containsExactly("SUPERSEDED");
    }

    @Test
    void statusValuesDependOnEntityType() {
        BrainVocabulary v = new VocabularyRegistry().core();
        assertThat(v.attribute("status", "Task").orElseThrow().enumValues()).contains("IN_PROGRESS");
        assertThat(v.attribute("status", "Project").orElseThrow().enumValues()).doesNotContain("IN_PROGRESS");
        assertThat(v.attribute("status", "Technology")).isEmpty();
        assertThat(v.attribute("homepage", "Project").orElseThrow().valueType()).isEqualTo(ValueType.URL);
    }
}
