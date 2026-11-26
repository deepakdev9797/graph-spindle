package io.graphspindle.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.vocabulary.NameSimilarity.Kind;
import io.graphspindle.vocabulary.NameSimilarity.Term;
import java.util.List;
import org.junit.jupiter.api.Test;

class NameSimilarityTest {

    private static final List<Term> TERMS = List.of(
            new Term("Person", Kind.NODE_TYPE, "Person"),
            new Term("human", Kind.NODE_TYPE_SYNONYM, "Person"),
            new Term("Project", Kind.NODE_TYPE, "Project"),
            new Term("Task", Kind.NODE_TYPE, "Task"),
            new Term("Category", Kind.NODE_TYPE, "Category"),
            new Term("developer", Kind.PROPERTY_SYNONYM, "Person.role"),
            new Term("MEMBER_OF", Kind.RELATIONSHIP_TYPE, "MEMBER_OF"));

    private static String target(String candidate) {
        return NameSimilarity.findSimilar(candidate, TERMS).map(NameSimilarity.Match::target).orElse(null);
    }

    @Test
    void sameNameIgnoringCaseAndPunctuation() {
        assertThat(NameSimilarity.findSimilar("PERSON", TERMS).orElseThrow().reason()).isEqualTo("SAME_NAME");
        assertThat(target("Member_Of")).isEqualTo("MEMBER_OF");
        assertThat(target("MEMBEROF")).isEqualTo("MEMBER_OF");
        assertThat(target("Human")).isEqualTo("Person");
    }

    @Test
    void propertySynonymGivesAPropertyHint() {
        NameSimilarity.Match m = NameSimilarity.findSimilar("Developer", TERMS).orElseThrow();
        assertThat(m.target()).isEqualTo("Person.role");
        assertThat(m.hint("Developer")).contains("Person.role");
        assertThat(target("Developers")).isEqualTo("Person.role");
    }

    @Test
    void singularAndPlural() {
        assertThat(NameSimilarity.findSimilar("Projects", TERMS).orElseThrow().reason()).isEqualTo("SINGULAR_PLURAL");
        assertThat(target("Tasks")).isEqualTo("Task");
        assertThat(target("Categories")).isEqualTo("Category");
        assertThat(target("Humans")).isEqualTo("Person");
    }

    @Test
    void editDistanceOnlyForNamesOfAtLeastFiveCharacters() {
        assertThat(NameSimilarity.findSimilar("Persn", TERMS).orElseThrow().reason()).isEqualTo("EDIT_DISTANCE");
        assertThat(target("Projekt")).isEqualTo("Project");
        assertThat(target("Prsoon")).isEqualTo("Person");
        // short names are compared exactly only: "Desk" is 2 edits from "Task" but has 4 characters
        assertThat(target("Desk")).isNull();
        assertThat(target("Recipe")).isNull();
        assertThat(target("Vehicle")).isNull();
        assertThat(target("Personality")).isNull();
    }

    @Test
    void helpers() {
        assertThat(NameSimilarity.normalize("Job Title")).isEqualTo("jobtitle");
        assertThat(NameSimilarity.normalize(null)).isEmpty();
        assertThat(NameSimilarity.singular("classes")).isEqualTo("class");
        assertThat(NameSimilarity.singular("class")).isEqualTo("class");
        assertThat(NameSimilarity.levenshtein("kitten", "sitting", 5)).isEqualTo(3);
        assertThat(NameSimilarity.levenshtein("abcdef", "uvwxyz", 2)).isEqualTo(3);
        assertThat(NameSimilarity.findSimilar("", TERMS)).isEmpty();
    }
}
