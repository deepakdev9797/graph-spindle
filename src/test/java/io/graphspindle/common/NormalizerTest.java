package io.graphspindle.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;

class NormalizerTest {

    static Stream<Arguments> table() {
        return Stream.of(
                // symbols are kept: C++, C#, C stay distinct
                Arguments.of("C++", "c++"),
                Arguments.of("C#", "c#"),
                Arguments.of("C", "c"),
                Arguments.of("F#", "f#"),
                Arguments.of("C++.", "c++"),
                Arguments.of("Node.js", "node.js"),
                Arguments.of(".NET", ".net"),
                Arguments.of("Apache-2.0", "apache-2.0"),
                Arguments.of("Apache 2.0", "apache 2.0"),
                Arguments.of("CI/CD", "ci/cd"),
                Arguments.of("Objective-C", "objective-c"),
                // case and whitespace
                Arguments.of("  Neo4j  ", "neo4j"),
                Arguments.of("THE NEO4J", "neo4j"),
                Arguments.of("Graph   Spindle", "graph spindle"),
                Arguments.of("Spring\tAI", "spring ai"),
                Arguments.of("Spring\n\r\nAI", "spring ai"),
                Arguments.of("Spring AI", "spring ai"),
                Arguments.of("Spring　AI", "spring ai"),
                // leading "the"
                Arguments.of("The Neo4j", "neo4j"),
                Arguments.of("the   Neo4j", "neo4j"),
                Arguments.of("Theory", "theory"),
                Arguments.of("The", "the"),
                Arguments.of("\"The Beatles\"", "beatles"),
                // diacritics
                Arguments.of("Café", "cafe"),
                Arguments.of("Zürich", "zurich"),
                Arguments.of("naïve", "naive"),
                Arguments.of("Ångström", "angstrom"),
                Arguments.of("école", "ecole"),
                Arguments.of("İstanbul", "istanbul"),
                // NFKC: full width, ligatures, compatibility forms
                Arguments.of("Ｎｅｏ４ｊ", "neo4j"),
                Arguments.of("ﬁle", "file"),
                Arguments.of("Ⅻ", "xii"),
                // zero-width and bidi controls
                Arguments.of("Neo​4j", "neo4j"),
                Arguments.of("Graph‍Spindle", "graphspindle"),
                Arguments.of("‮abc‬", "abc"),
                Arguments.of("﻿Neo4j", "neo4j"),
                // surrounding quotes and brackets, trailing punctuation
                Arguments.of("\"GraphSpindle\"", "graphspindle"),
                Arguments.of("'GraphSpindle'", "graphspindle"),
                Arguments.of("“Spring AI”", "spring ai"),
                Arguments.of("(GraphSpindle)", "graphspindle"),
                Arguments.of("[Neo4j]", "neo4j"),
                Arguments.of("Neo4j.", "neo4j"),
                Arguments.of("Neo4j!?", "neo4j"),
                Arguments.of("Foo (bar)", "foo (bar)"),
                Arguments.of("\"(Neo4j).\"", "neo4j"),
                // empty results
                Arguments.of("", ""),
                Arguments.of("   ", ""),
                Arguments.of("​‌", ""),
                Arguments.of("...", ""),
                Arguments.of("\"\"", ""));
    }

    @ParameterizedTest(name = "norm({0}) = {1}")
    @MethodSource("table")
    void normalizesPerSchemaSection73(String input, String expected) {
        assertThat(Normalizer.norm(input)).isEqualTo(expected);
    }

    @Test
    void tableHasAtLeastThirtyCases() {
        assertThat(table().count()).isGreaterThanOrEqualTo(30);
    }

    @Test
    void cPlusPlusCSharpAndCStayDistinct() {
        assertThat(Stream.of("C++", "C#", "C", "c++ ", " C#").map(Normalizer::norm).distinct())
                .containsExactlyInAnyOrder("c++", "c#", "c");
    }

    @Test
    void nullIsEmpty() {
        assertThat(Normalizer.norm(null)).isEmpty();
        assertThat(Normalizer.normalizeName(null)).isEmpty();
        assertThat(Normalizer.normalizeName("  ")).isEmpty();
        assertThat(Normalizer.normalizeName(" Neo4j ")).contains("neo4j");
    }

    @Test
    void truncatesToTwoHundredCodePoints() {
        assertThat(Normalizer.norm("a".repeat(250))).hasSize(200);
        String emoji = "😀".repeat(250);
        String n = Normalizer.norm(emoji);
        assertThat(n.codePointCount(0, n.length())).isEqualTo(200);
        assertThat(Character.isHighSurrogate(n.charAt(n.length() - 1))).isFalse();
        // no trailing whitespace after truncation
        assertThat(Normalizer.norm("a".repeat(199) + " bcd")).isEqualTo("a".repeat(199));
    }

    @Test
    void isIdempotent() {
        table().map(a -> (String) a.get()[0]).forEach(s ->
                assertThat(Normalizer.norm(Normalizer.norm(s))).as(s).isEqualTo(Normalizer.norm(s)));
    }

    @Test
    void isLinearOnHugeInput() {
        String huge = ("x​ ".repeat(400_000)) + "\"";
        long start = System.nanoTime();
        Normalizer.norm(huge);
        assertThat(System.nanoTime() - start).isLessThan(5_000_000_000L);
    }

    @Test
    void canonicalKeyIsTypeLowercasePlusName() {
        assertThat(Normalizer.canonicalKey("Technology", Normalizer.norm("The Neo4j"))).isEqualTo("technology:neo4j");
        assertThat(Normalizer.canonicalKey("Standard", "apache license 2.0")).isEqualTo("standard:apache license 2.0");
        assertThatThrownBy(() -> Normalizer.canonicalKey("Project", "")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "HTTPS://Example.COM:443/Path/?q=1#frag | https://example.com/Path?q=1",
            "http://example.com:80/                 | http://example.com",
            "http://example.com:8080/a/b/           | http://example.com:8080/a/b",
            "https://example.com                    | https://example.com",
            "file:/tmp/x                            | file:/tmp/x",
            "not a url                              | not a url"})
    void normalizesUrls(String input, String expected) {
        assertThat(Normalizer.normalizeUrl(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "docs/ARCHITECTURE.md      | docs/ARCHITECTURE.md",
            "./docs//ARCHITECTURE.md   | docs/ARCHITECTURE.md",
            "docs\\\\ARCHITECTURE.md   | docs/ARCHITECTURE.md",
            "/home/u/./a//b            | /home/u/a/b"})
    void normalizesFilePaths(String input, String expected) {
        assertThat(Normalizer.normalizeFilePath(input)).isEqualTo(expected);
    }

    @Test
    void untrustedPreviewIsShortAndSingleLine() {
        String p = Untrusted.preview("line1\nline2 " + "x".repeat(500));
        assertThat(p).doesNotContain("\n").endsWith("…");
        assertThat(p.length()).isLessThanOrEqualTo(Untrusted.PREVIEW_CHARS + 1);
    }
}
