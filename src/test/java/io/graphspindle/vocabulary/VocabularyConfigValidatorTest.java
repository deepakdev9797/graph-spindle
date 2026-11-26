package io.graphspindle.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.vocabulary.VocabularyConfig.CustomAttribute;
import io.graphspindle.vocabulary.VocabularyConfig.CustomAttributePredicate;
import io.graphspindle.vocabulary.VocabularyConfig.CustomEntityType;
import io.graphspindle.vocabulary.VocabularyConfig.CustomRelationPredicate;
import io.graphspindle.vocabulary.VocabularyConfig.PredicateOverride;
import io.graphspindle.vocabulary.VocabularyIssue.Code;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VocabularyConfigValidatorTest {

    private final VocabularyConfigValidator validator = new VocabularyConfigValidator();

    /** The example from SCHEMA §11. */
    static VocabularyConfig schemaExample() {
        return new VocabularyConfig("core-v1",
                List.of("Person", "Project", "Technology", "Standard", "Decision", "Task", "Concept"),
                List.of("RELATED_TO"),
                Map.of("LICENSED_UNDER", new PredicateOverride(Cardinality.MANY)),
                List.of(new CustomEntityType("Recipe", "A cooking recipe", null, List.of(
                        new CustomAttribute("cuisine", ValueType.STRING, 50, null, null, null),
                        new CustomAttribute("difficulty", ValueType.ENUM, null, List.of("EASY", "MEDIUM", "HARD"),
                                null, null)))),
                List.of(new CustomRelationPredicate("INGREDIENT_OF", "is an ingredient of", List.of("Concept"),
                        List.of("Recipe"), Cardinality.MANY)),
                List.of(new CustomAttributePredicate("servings", List.of("Recipe"), ValueType.NUMBER, null, null,
                        BigDecimal.ONE, BigDecimal.valueOf(100))));
    }

    private List<Code> codes(VocabularyConfig config) {
        return validator.validate(config).stream().map(VocabularyIssue::code).toList();
    }

    private static VocabularyConfig withTypes(List<CustomEntityType> types) {
        return new VocabularyConfig(null, null, null, null, types, null, null);
    }

    private static VocabularyConfig withRelations(List<CustomRelationPredicate> relations) {
        return new VocabularyConfig(null, null, null, null, null, relations, null);
    }

    private static CustomEntityType type(String name) {
        return new CustomEntityType(name, "a custom type", null, List.of());
    }

    @Test
    void acceptsCoreAndTheSchemaExample() {
        assertThat(validator.validate(VocabularyConfig.core())).isEmpty();
        assertThat(validator.validate(schemaExample())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Entity", "Alias", "Claim", "Ingestion", "SourceRef", "Conflict", "Brain", "ApiKey",
            "System", "Internal", "Project", "Person"})
    void rejectsReservedLabelsAndCoreTypesAsCustomTypes(String name) {
        assertThat(codes(withTypes(List.of(type(name))))).containsExactly(Code.RESERVED_NAME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"recipe", "R", "_Recipe", "Recipe-Card", "Recipe Card", "Récipe", "Recipe$",
            "AVeryLongTypeNameThatGoesOnAndOnForeverAndEver"})
    void rejectsBadTypeNames(String name) {
        assertThat(codes(withTypes(List.of(type(name))))).containsExactly(Code.INVALID_NAME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ingredient_of", "I", "INGREDIENT-OF", "1NGREDIENT", "INGREDIENT OF"})
    void rejectsBadRelationNames(String name) {
        assertThat(codes(withRelations(List.of(new CustomRelationPredicate(name, "d", List.of("Concept"),
                List.of("Project"), null))))).containsExactly(Code.INVALID_NAME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HAS_ALIAS", "SUBJECT", "SUPERSEDES", "MERGED_INTO", "USES", "RELATED_TO"})
    void rejectsSystemAndCoreRelationNames(String name) {
        assertThat(codes(withRelations(List.of(new CustomRelationPredicate(name, "d", List.of("Concept"),
                List.of("Project"), null))))).containsExactly(Code.RESERVED_NAME);
    }

    @Test
    void rejectsBadAttributeNamesAndEnumValues() {
        VocabularyConfig config = withTypes(List.of(new CustomEntityType("Recipe", "r", null, List.of(
                new CustomAttribute("Cuisine", ValueType.STRING, null, null, null, null),
                new CustomAttribute("difficulty", ValueType.ENUM, null, List.of("easy", "HARD", "HARD"), null, null),
                new CustomAttribute("notes", ValueType.STRING, 501, null, null, null),
                new CustomAttribute("level", ValueType.ENUM, null, List.of(), null, null),
                new CustomAttribute("kind", null, null, null, null, null),
                new CustomAttribute("size", ValueType.STRING, null, List.of("A"), null, null),
                new CustomAttribute("weight", ValueType.NUMBER, null, null, BigDecimal.TEN, BigDecimal.ONE)))));
        assertThat(codes(config)).containsExactlyInAnyOrder(Code.INVALID_NAME, Code.INVALID_VALUE,
                Code.DUPLICATE_NAME, Code.INVALID_VALUE, Code.INVALID_VALUE, Code.INVALID_VALUE, Code.INVALID_VALUE,
                Code.INVALID_VALUE);
    }

    @Test
    void rejectsOverLimitCustomTypeCount() {
        List<CustomEntityType> types = IntStream.range(0, 21).mapToObj(i -> type("Custom" + (char) ('A' + i))).toList();
        assertThat(codes(withTypes(types))).contains(Code.LIMIT_EXCEEDED);
        assertThat(codes(withTypes(types.subList(0, 20)))).doesNotContain(Code.LIMIT_EXCEEDED);
    }

    @Test
    void rejectsOverLimitCustomRelationCount() {
        List<CustomRelationPredicate> rels = IntStream.range(0, 31).mapToObj(i -> new CustomRelationPredicate(
                "CUSTOM_" + i, "d", List.of("Concept"), List.of("Project"), Cardinality.MANY)).toList();
        assertThat(codes(withRelations(rels))).contains(Code.LIMIT_EXCEEDED);
    }

    @Test
    void rejectsTooManyAttributesPerTypeAndTooManyEnumValues() {
        List<CustomAttribute> attrs = IntStream.range(0, 11).mapToObj(i -> new CustomAttribute("attr" + (char) ('a' + i),
                ValueType.STRING, null, null, null, null)).toList();
        assertThat(codes(withTypes(List.of(new CustomEntityType("Recipe", "r", null, attrs)))))
                .containsExactly(Code.LIMIT_EXCEEDED);
        List<String> values = IntStream.range(0, 31).mapToObj(i -> "V" + i).toList();
        assertThat(codes(withTypes(List.of(new CustomEntityType("Recipe", "r", null, List.of(
                new CustomAttribute("level", ValueType.ENUM, null, values, null, null)))))))
                .containsExactly(Code.LIMIT_EXCEEDED);
    }

    @Test
    void rejectsTotalPredicateCountOverSixty() {
        // core: 28 relations + 13 attribute names = 41; 20 more relations makes 61
        List<CustomRelationPredicate> rels = IntStream.range(0, 20).mapToObj(i -> new CustomRelationPredicate(
                "CUSTOM_" + i, "d", List.of("Concept"), List.of("Project"), Cardinality.MANY)).toList();
        assertThat(codes(withRelations(rels))).containsExactly(Code.LIMIT_EXCEEDED);
        assertThat(codes(withRelations(rels.subList(0, 19)))).isEmpty();
        // disabling a core predicate makes room
        assertThat(codes(new VocabularyConfig(null, null, List.of("RELATED_TO"), null, null, rels, null))).isEmpty();
    }

    @Test
    void rejectsUnknownTypesInPredicates() {
        assertThat(codes(withRelations(List.of(new CustomRelationPredicate("COOKS", "d", List.of("Chef"),
                List.of("Recipe"), null))))).containsExactly(Code.UNKNOWN_TYPE, Code.UNKNOWN_TYPE);
        // a type that exists in core but is not enabled for the brain
        VocabularyConfig disabled = new VocabularyConfig(null, List.of("Person", "Project"), null, null, null,
                List.of(new CustomRelationPredicate("SPONSORS", "d", List.of("Organization"), List.of("Project"), null)),
                null);
        assertThat(codes(disabled)).containsExactly(Code.UNKNOWN_TYPE);
        assertThat(codes(new VocabularyConfig(null, null, null, null, null, null, List.of(
                new CustomAttributePredicate("servings", List.of("Recipe"), ValueType.NUMBER, null, null, null, null)))))
                .containsExactly(Code.UNKNOWN_TYPE);
        assertThat(codes(withRelations(List.of(new CustomRelationPredicate("COOKS", "d", List.of(), List.of("Person"),
                null))))).containsExactly(Code.INVALID_VALUE);
    }

    @Test
    void rejectsDisablingPerson() {
        assertThat(codes(new VocabularyConfig(null, List.of("Project", "Technology"), null, null, null, null, null)))
                .containsExactly(Code.CANNOT_DISABLE);
        assertThat(codes(new VocabularyConfig(null, List.of(), null, null, null, null, null)))
                .containsExactly(Code.CANNOT_DISABLE);
    }

    @Test
    void rejectsDisablingSystemNamesAndUnknownPredicates() {
        assertThat(codes(new VocabularyConfig(null, null, List.of("HAS_ALIAS", "Entity", "FLIES_TO", "USES", "USES"),
                null, null, null, null)))
                .containsExactly(Code.CANNOT_DISABLE, Code.CANNOT_DISABLE, Code.UNKNOWN_PREDICATE, Code.DUPLICATE_NAME);
    }

    @Test
    void rejectsUnknownEnabledTypesAndProfile() {
        assertThat(codes(new VocabularyConfig("core-v2", List.of("Person", "Wizard"), null, null, null, null, null)))
                .containsExactly(Code.UNKNOWN_PROFILE, Code.UNKNOWN_TYPE);
    }

    @Test
    void overridesOnlyKnownEnabledPredicatesAndNeedCardinality() {
        assertThat(codes(new VocabularyConfig(null, null, List.of("RELATED_TO"), Map.of(
                "FLIES_TO", new PredicateOverride(Cardinality.ONE),
                "RELATED_TO", new PredicateOverride(Cardinality.ONE),
                "USES", new PredicateOverride(null)), null, null, null)))
                .containsExactlyInAnyOrder(Code.UNKNOWN_PREDICATE, Code.UNKNOWN_PREDICATE, Code.INVALID_VALUE);
    }

    @Test
    void rejectsDuplicatesAcrossCustomDefinitions() {
        VocabularyConfig config = new VocabularyConfig(null, null, null, null,
                List.of(type("Recipe"), type("Recipe"),
                        new CustomEntityType("Dish", "d", null,
                                List.of(new CustomAttribute("cuisine", ValueType.STRING, null, null, null, null)))),
                null,
                List.of(new CustomAttributePredicate("cuisine", List.of("Dish"), ValueType.STRING, null, null, null,
                                null),
                        new CustomAttributePredicate("status", List.of("Project"), ValueType.STRING, null, null, null,
                                null)));
        assertThat(codes(config)).containsExactly(Code.DUPLICATE_NAME, Code.DUPLICATE_NAME, Code.DUPLICATE_NAME);
    }

    @Test
    void checksDescriptionsAndGroups() {
        assertThat(codes(withTypes(List.of(new CustomEntityType("Recipe", " ", "FOOD", List.of())))))
                .containsExactly(Code.INVALID_VALUE, Code.INVALID_VALUE);
        assertThat(codes(withTypes(List.of(new CustomEntityType("Recipe", "x".repeat(201), "WORK", List.of())))))
                .containsExactly(Code.INVALID_VALUE);
    }

    @Test
    void issuesCarryPaths() {
        List<VocabularyIssue> issues = new ArrayList<>(validator.validate(withTypes(List.of(type("ok"), type("Entity")))));
        assertThat(issues).extracting(VocabularyIssue::path)
                .containsExactly("customEntityTypes[0].name", "customEntityTypes[1].name");
    }
}
