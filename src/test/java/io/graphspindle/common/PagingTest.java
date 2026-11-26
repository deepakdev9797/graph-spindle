package io.graphspindle.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PagingTest {

    @Test
    void limits() {
        assertThat(Paging.limit(null, 10, 50)).isEqualTo(10);
        assertThat(Paging.limit(50, 10, 50)).isEqualTo(50);
        assertThatThrownBy(() -> Paging.limit(51, 10, 50)).extracting(e -> ((GraphSpindleException) e).code())
                .isEqualTo(ErrorCode.LIMIT_EXCEEDED);
        assertThatThrownBy(() -> Paging.limit(0, 10, 50)).extracting(e -> ((GraphSpindleException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void cursorsAreIdsOnly() {
        assertThat(Paging.cursor(null)).isNull();
        assertThat(Paging.cursor("0190a3c4-0000-7000-8000-000000000000")).isNotNull();
        assertThatThrownBy(() -> Paging.cursor("' OR 1=1")).isInstanceOf(GraphSpindleException.class);
    }

    @Test
    void pageOfLimitPlusOneRows() {
        Page<String> p = Page.of(List.of("a", "b", "c"), 2, s -> s);
        assertThat(p.items()).containsExactly("a", "b");
        assertThat(p.next()).isEqualTo("b");
        assertThat(Page.of(List.of("a"), 2, s -> s).next()).isNull();
    }
}
