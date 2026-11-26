package io.graphspindle.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.common.Normalizer;
import io.graphspindle.graph.internal.Lucene;
import io.graphspindle.vocabulary.VocabularyRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class NameMatchTest {

    @Test
    void compactKeysIgnoreCaseSpacingPunctuationAndALeadingThe() {
        assertThat(List.of("GraphSpindle", "Graph Spindle", "graph-spindle", "The GraphSpindle", "\"GraphSpindle.\"",
                "Graph_Spindle")).allSatisfy(n -> assertThat(Normalizer.compact(n)).isEqualTo("graphspindle"));
        assertThat(Normalizer.compact("C++")).isEqualTo("c++");
        assertThat(Normalizer.compact("C#")).isEqualTo("c#");
        assertThat(Normalizer.compact("C")).isEqualTo("c");
        assertThat(Normalizer.compact("The")).isEqualTo("the");
        assertThat(Normalizer.compact("...")).isEmpty();
    }

    @Test
    void similarityIsExactForShortNamesAndEditDistanceBasedOtherwise() {
        assertThat(NameMatch.similarity("Graph Spindle", "GraphSpindle")).isEqualTo(1.0);
        assertThat(NameMatch.similarity("John Smith", "Jon Smith")).isBetween(0.85, 0.95);
        assertThat(NameMatch.similarity("Go", "Gx")).isZero();
        assertThat(NameMatch.similarity("C++", "C#")).isZero();
        assertThat(NameMatch.similarity("Neo4j", "Neo4j Aura")).isLessThan(0.85);
        assertThat(NameMatch.similarity("", "x")).isZero();
        assertThat(NameMatch.best("postgres", List.of("postgresql", "postgres"))).isEqualTo(1.0);
        assertThat(NameMatch.levenshtein("kitten", "sitting")).isEqualTo(3);
    }

    @Test
    void fuzzyTermsAddOneTypoVariantsOfLongerPlainWords() {
        assertThat(Lucene.fuzzyTerms("Graphspindel go")).isEqualTo("Graphspindel go Graphspindelgo Graphspindel~1"
                + " Graphspindelgo~1");
        assertThat(Lucene.fuzzyTerms("C++")).isEqualTo("C\\+\\+");
        assertThat(Lucene.fuzzyTerms("  ")).isEmpty();
    }

    @Test
    void mentionTypesMayBeTypeSynonyms() {
        var v = new VocabularyRegistry().core();
        assertThat(v.typeFor("company")).contains("Organization");
        assertThat(v.typeFor("PROJECT")).contains("Project");
        assertThat(v.typeFor("Technology")).contains("Technology");
        assertThat(v.typeFor("spaceship")).isEmpty();
        assertThat(v.typeFor(null)).isEmpty();
    }
}
