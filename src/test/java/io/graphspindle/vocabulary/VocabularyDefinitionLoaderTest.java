package io.graphspindle.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class VocabularyDefinitionLoaderTest {

    private static InputStream yaml(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static InvalidVocabularyDefinitionException invalid(String yaml) {
        try {
            VocabularyDefinitionLoader.loadStandalone(yaml(yaml), "test.yml");
        } catch (InvalidVocabularyDefinitionException e) {
            return e;
        }
        throw new AssertionError("expected the definition to be rejected");
    }

    private static final String MINIMAL = """
            profile: core-v1
            entityTypes:
              - name: Person
                description: A human
            """;

    @Test
    void bundledDefinitionLoadsIntoTheImmutableRecords() {
        CoreProfile p = VocabularyDefinitionLoader.bundled();
        assertThat(p.name()).isEqualTo("core-v1");
        assertThat(p.source()).isEqualTo("classpath:vocabulary/core-v1.yml");
        assertThat(p.entityTypes()).hasSize(15).allMatch(EntityTypeDef::core);
        assertThat(p.relationPredicates()).hasSize(28);
        assertThat(p.classificationHints()).hasSize(5);
        assertThat(p.classificationHints().get(0).requiresType()).isEqualTo("Technology");
        assertThat(p.relation("PART_OF").orElseThrow().extraTargetsBySource()).containsKeys("Task", "Artifact");
        assertThat(p.relation("ABOUT").orElseThrow().targetTypes().anyType()).isTrue();
        // identical property definitions on several types are grouped into one predicate definition
        assertThat(p.attributePredicates().stream().filter(a -> a.name().equals("homepage")).toList()).hasSize(1)
                .first().satisfies(a -> assertThat(a.entityTypes()).containsExactlyInAnyOrder("Project",
                        "Technology", "Standard", "Organization", "Artifact"));
        // differing descriptions stay separate definitions
        assertThat(p.attributePredicates().stream().filter(a -> a.name().equals("status")).count()).isEqualTo(4);
        assertThat(p.compatibilityGroups()).containsOnlyKeys("ACTOR", "WORK", "TECHNICAL", "OBJECTIVE");
        assertThat(CoreVocabulary.DEFAULT.entityTypes()).isEqualTo(p.entityTypes());
    }

    @Test
    void minimalStandaloneDefinitionIsValid() {
        CoreProfile p = VocabularyDefinitionLoader.loadStandalone(yaml(MINIMAL), "test.yml");
        assertThat(p.entityTypes()).extracting(EntityTypeDef::name).containsExactly("Person");
        assertThat(new VocabularyRegistry(p).core().entityTypeNames()).containsExactly("Person");
    }

    @Test
    void unreadableYamlFailsWithLocation() {
        InvalidVocabularyDefinitionException e = invalid("profile: core-v1\nentityTypes: [ {name: Person\n");
        assertThat(e.getMessage()).contains("test.yml").contains("unreadable YAML").contains("line");
    }

    @Test
    void unknownFieldsAndDuplicateKeysAreRejected() {
        assertThat(invalid(MINIMAL + "    colour: blue\n").getMessage()).contains("colour");
        assertThat(invalid("profile: core-v1\nprofile: core-v1\nentityTypes: []\n").getMessage())
                .containsIgnoringCase("duplicate");
        assertThat(invalid(MINIMAL.replace("description: A human", "description: A human\n    properties:\n"
                + "      - {name: age, type: INTEGER, description: age}")).getMessage()).contains("INTEGER");
    }

    @Test
    void everyProblemIsListed() {
        InvalidVocabularyDefinitionException e = invalid("""
                profile: core-v2
                entityTypes:
                  - name: person
                  - name: Entity
                    description: reserved
                  - name: Robot
                    description: A robot
                    group: actors
                    synonyms: ["", droid]
                    properties:
                      - name: Serial
                        type: STRING
                        maxLength: 900
                        description: serial number
                      - name: state
                        type: ENUM
                        values: [ON, OFF]
                        engineOnlyValues: [BROKEN]
                      - name: weight
                        type: STRING
                        min: 1
                        description: weight
                relationshipTypes:
                  - name: SUBJECT
                    description: system name
                    sources: [Robot]
                    targets: [Robot]
                  - name: OWNS
                    description: owns
                    sources: [Robot, Spaceship]
                    targets: []
                  - name: LIKES
                    sources: [Any, Robot]
                    targets: [Any]
                    confidenceCap: 2
                classificationHints:
                  - text: "Robots are Robots."
                    requiresType: Android
                """);
        assertThat(e.problems()).anyMatch(p -> p.startsWith("profile"))
                .anyMatch(p -> p.contains("entityTypes[0:person].name"))
                .anyMatch(p -> p.contains("entityTypes[0:person].description: is required"))
                .anyMatch(p -> p.contains("reserved label"))
                .anyMatch(p -> p.contains(".group"))
                .anyMatch(p -> p.contains("synonyms: entries must not be blank"))
                .anyMatch(p -> p.contains("properties[0:Serial].name"))
                .anyMatch(p -> p.contains("maxLength must be between 1 and 500"))
                .anyMatch(p -> p.contains("properties[1:state].description: is required"))
                .anyMatch(p -> p.contains("engineOnlyValues: 'BROKEN'"))
                .anyMatch(p -> p.contains("min/max are only allowed for NUMBER"))
                .anyMatch(p -> p.contains("Person is required"))
                .anyMatch(p -> p.contains("system relationship type"))
                .anyMatch(p -> p.contains("unknown entity type 'Spaceship'"))
                .anyMatch(p -> p.contains("OWNS].targets: at least one target type"))
                .anyMatch(p -> p.contains("LIKES].description: is required"))
                .anyMatch(p -> p.contains("Any cannot be combined"))
                .anyMatch(p -> p.contains("confidenceCap"))
                .anyMatch(p -> p.contains("requiresType: unknown entity type 'Android'"));
        assertThat(e.getMessage()).contains("test.yml").contains(e.problems().size() + " problem(s)");
    }

    @Test
    void overrideIsMergedByNameOverTheBundledDefinition() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/vocabulary/override-valid.yml")) {
            CoreProfile p = VocabularyDefinitionLoader.load(in, "override-valid.yml");
            assertThat(p.source()).contains("core-v1.yml").contains("override-valid.yml");
            assertThat(p.entityTypes()).extracting(EntityTypeDef::name).contains("Person", "Vehicle", "Place")
                    .doesNotContain("Preference").hasSize(15);
            assertThat(p.entityType("Place").orElseThrow().description()).isEqualTo("A physical location");
            assertThat(p.attributesOf("Place")).extracting(AttributePredicateDef::name).containsExactly("address");
            assertThat(p.relation("DRIVES")).isPresent();
            assertThat(p.relation("HAS_PREFERENCE")).isEmpty();
            assertThat(p.relation("APPLIES_TO").orElseThrow().sourceTypes().types()).containsExactly("Decision");
            // a brain resolved against the override sees the new type
            BrainVocabulary v = new VocabularyRegistry(p).core();
            assertThat(v.allowsRelation("DRIVES", "Person", "Vehicle")).isTrue();
            assertThat(v.attribute("plate", "Vehicle")).isPresent();
        }
    }

    @Test
    void overrideReferencingARemovedTypeOrInvalidPropertyFails() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/vocabulary/override-invalid.yml")) {
            assertThatThrownBy(() -> VocabularyDefinitionLoader.load(in, "override-invalid.yml"))
                    .isInstanceOf(InvalidVocabularyDefinitionException.class)
                    .hasMessageContaining("override-invalid.yml")
                    .hasMessageContaining("entityTypes[15:Vehicle].description: is required")
                    .hasMessageContaining("properties[0:Plate].name");
        }
        assertThatThrownBy(() -> VocabularyDefinitionLoader.load(yaml("removeEntityTypes: [Person]\n"), "x.yml"))
                .hasMessageContaining("Person is required");
        // removing a type that core relationship types still reference is reported, not silently patched
        assertThatThrownBy(() -> VocabularyDefinitionLoader.load(yaml("removeEntityTypes: [Place]\n"), "x.yml"))
                .hasMessageContaining("LOCATED_IN].targets: unknown entity type 'Place'");
        assertThatThrownBy(() -> VocabularyDefinitionLoader.loadStandalone(yaml(MINIMAL
                + "removeEntityTypes: [Person]\n"), "x.yml")).hasMessageContaining("only allowed in an override");
    }
}
