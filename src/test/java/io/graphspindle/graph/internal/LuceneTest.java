package io.graphspindle.graph.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LuceneTest {

    @Test
    void scopedQueryRequiresTheBrainTokenAndKeepsUserTermsInTheTextFields() {
        assertThat(Lucene.scoped("dev-1", "graph spindle", "name", "description")).isEqualTo(
                "+ftScope:dev_1 +(name:(graph spindle graphspindle) description:(graph spindle graphspindle))");
        assertThat(Lucene.scoped("dev", "  ", "name")).isEmpty();
    }

    @Test
    void userTextCannotEscapeTheGroupOrNameTheScopeField() {
        String q = Lucene.scoped("a", "x) ftScope:b OR (y", "name");
        assertThat(q).startsWith("+ftScope:a +(name:(").contains("x\\)", "ftScope\\:b", "or", "\\(y")
                .doesNotContain(" OR ");
    }

    @Test
    void termsAddAdjacentAndFullConcatenations() {
        assertThat(Lucene.terms("a b c")).isEqualTo("a b c ab bc abc");
        assertThat(Lucene.terms("one")).isEqualTo("one");
        assertThat(Lucene.terms("apache-2.0")).isEqualTo("apache\\-2.0");
        assertThat(Lucene.scopeToken("x-qa-fa")).isEqualTo("x_qa_fa");
    }
}
