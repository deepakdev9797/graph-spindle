package io.graphspindle.ingestion.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class EvidenceCheckerTest {

    private final EvidenceChecker checker = new EvidenceChecker(
            "I am building GraphSpindle in Java.\nIt uses   Neo4j and Spring AI. We’re switching to the “MIT” license.",
            List.of("Architecture of GraphSpindle", ""));

    @ParameterizedTest
    @ValueSource(strings = {"I am building GraphSpindle", "building graphspindle IN JAVA", "Java. It uses Neo4j",
            "It uses Neo4j and Spring AI", "  uses neo4j  ", "We're switching", "the \"MIT\" license",
            "We’re switching", "Neo​4j and", "ARCHITECTURE of graphspindle", "ｂｕｉｌｄｉｎｇ ＧｒａｐｈＳｐｉｎｄｌｅ"})
    void acceptsVerbatimQuotesModuloWhitespaceCaseAndQuotes(String evidence) {
        assertThat(checker.isSupported(evidence)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GraphSpindle is written in Java", "It relies on Neo4j", "I build GraphSpindle",
            "uses Neo4j, Spring AI", "the MIT license", "switching to Apache", "", "  ", "AI", "I",
            "GraphSpindleinJava"})
    void rejectsParaphrasesInventionsAndTrivialQuotes(String evidence) {
        assertThat(checker.isSupported(evidence)).isFalse();
    }

    @Test
    void nullEvidenceIsNotSupported() {
        assertThat(checker.isSupported(null)).isFalse();
        assertThat(new EvidenceChecker(null, null).isSupported("anything")).isFalse();
    }
}
