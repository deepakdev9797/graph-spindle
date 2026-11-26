package io.graphspindle.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.graph.ClaimView;
import io.graphspindle.graph.TimedClaim;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Record-time belief rules of point-in-time reads (ADR-026). */
class NodeViewBeliefTest {

    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");

    private static TimedClaim claim(String id, String status, String polarity, int recordedMin, int changedMin,
            String... supersedes) {
        ClaimView c = new ClaimView(id, "ATTRIBUTE", "role", "s", null, "STRING", "v-" + id, "v-" + id, polarity,
                "statement", status, 1.0, "MANUAL", "ONE", null, null, 0, null, null, "admin", null, null);
        return new TimedClaim(c, T0.plusSeconds(60L * recordedMin), T0.plusSeconds(60L * changedMin),
                List.of(supersedes), null);
    }

    private static List<String> believed(List<TimedClaim> timeline, int minute) {
        return NodeViewService.believedAt(timeline, T0.plusSeconds(60L * minute)).stream()
                .map(t -> t.claim().id()).toList();
    }

    @Test
    void activeClaimsCountFromRecordTimeAndEndedOnesUntilTheirStatusChange() {
        List<TimedClaim> timeline = List.of(claim("a", "ACTIVE", "POSITIVE", 1, 1),
                claim("r", "RETRACTED", "POSITIVE", 2, 5), claim("d", "DISPUTED", "POSITIVE", 3, 4));
        assertThat(believed(timeline, 0)).isEmpty();
        assertThat(believed(timeline, 1)).containsExactly("a");
        assertThat(believed(timeline, 3)).containsExactly("a", "r", "d");
        assertThat(believed(timeline, 4)).containsExactly("a", "r");
        assertThat(believed(timeline, 5)).containsExactly("a");
    }

    @Test
    void neverBelievedClaimsAreIgnored() {
        List<TimedClaim> timeline = List.of(claim("n", "ACTIVE", "NEGATIVE", 1, 1),
                claim("disputedAtOnce", "DISPUTED", "POSITIVE", 2, 2));
        assertThat(believed(timeline, 3)).isEmpty();
    }

    @Test
    void aSupersededClaimEndsWhenItsReplacementIsRecorded() {
        // the old claim's status change is stamped a little later than the replacement's record time
        List<TimedClaim> timeline = List.of(claim("old", "SUPERSEDED", "POSITIVE", 1, 6),
                claim("new", "ACTIVE", "POSITIVE", 5, 5, "old"));
        assertThat(believed(timeline, 4)).containsExactly("old");
        assertThat(believed(timeline, 5)).containsExactly("new");
        assertThat(NodeViewService.ends(timeline)).containsEntry("old", T0.plusSeconds(300));
    }

    @Test
    void asOfAcceptsDatesAndDateTimes() {
        assertThat(NodeViewService.parseAsOf("2026-11-20")).isEqualTo(Instant.parse("2026-11-20T23:59:59.999999999Z"));
        assertThat(NodeViewService.parseAsOf("2026-11-20T10:15:30Z")).isEqualTo(Instant.parse("2026-11-20T10:15:30Z"));
        assertThat(NodeViewService.parseAsOf("2026-11-20T10:15:30+02:00"))
                .isEqualTo(Instant.parse("2026-11-20T08:15:30Z"));
        assertThat(NodeViewService.parseAsOf("2026-11-20T10:15:30.5")).isEqualTo(Instant.parse("2026-11-20T10:15:30.5Z"));
        assertThat(NodeViewService.parseAsOf(null)).isNull();
        assertThat(NodeViewService.parseAsOf(" ")).isNull();
        assertThatThrownBy(() -> NodeViewService.parseAsOf("yesterday")).isInstanceOf(GraphSpindleException.class);
        assertThatThrownBy(() -> NodeViewService.parseAsOf("2026-13-01")).isInstanceOf(GraphSpindleException.class);
        assertThatThrownBy(() -> NodeViewService.parseAsOf("x".repeat(50))).isInstanceOf(GraphSpindleException.class);
    }
}
