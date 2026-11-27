package io.graphspindle.ingestion.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.ingestion.validation.EvidenceChecker;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * QA cc4929c: presentNear() falls back to "cue anywhere" when the evidence is not found by its own
 * literal word matcher, but EvidenceChecker accepts the same evidence after NFKC/dash/quote folding.
 * A model that writes '-' for an em dash (or quotes across sentences) gets a far-away cue counted.
 */
class QaM8CueProximityTest {

    final CorrectionCues cues = new CorrectionCues(List.of("actually"));
    static final String TEXT = "Actually the weather was great all week and the whole team enjoyed the offsite."
            + " GraphSpindle uses the MIT license — as of this week.";

    @Test
    void controlTheExactQuoteHasNoCueNearby() {
        assertThat(cues.presentNear(TEXT, "GraphSpindle uses the MIT license — as of this week")).isFalse();
    }

    @Test
    void aDashNormalizedQuoteIsAcceptedAsEvidenceButEscapesTheProximityCheck() {
        String evidence = "GraphSpindle uses the MIT license - as of this week";
        assertThat(new EvidenceChecker(TEXT, List.of()).isSupported(evidence)).isTrue();
        assertThat(cues.presentNear(TEXT, evidence)).as("cue is two sentences away").isFalse();
    }

    @Test
    void aZeroWidthCharacterInTheContentDoesTheSame() {
        String text = TEXT.replace("MIT", "M​IT");
        String evidence = "GraphSpindle uses the MIT license";
        assertThat(new EvidenceChecker(text, List.of()).isSupported(evidence)).isTrue();
        assertThat(cues.presentNear(text, evidence)).isFalse();
    }

    @Test
    void anEvidenceQuoteSpanningSentencesPullsInADistantCue() {
        String evidence = "the whole team enjoyed the offsite. GraphSpindle uses the MIT license";
        assertThat(new EvidenceChecker(TEXT, List.of()).isSupported(evidence)).isTrue();
        assertThat(cues.presentNear(TEXT, evidence)).isFalse();
    }
}
