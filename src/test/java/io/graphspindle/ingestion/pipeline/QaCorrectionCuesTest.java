package io.graphspindle.ingestion.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.ingestion.IngestionProperties;
import org.junit.jupiter.api.Test;

/** QA M6: the correction gate fires on everyday phrasing that is not a correction of anything. */
class QaCorrectionCuesTest {

    private final CorrectionCues cues = new CorrectionCues(IngestionProperties.DEFAULT_CORRECTION_CUES);

    @Test
    void everydayPhrasesAreNotCorrectionCues() {
        assertThat(cues.present("It is not only fast but GraphSpindle is GPL-3.0 licensed.")).isFalse();
        assertThat(cues.present("I moved the standup to Friday. GraphSpindle is GPL-3.0 licensed.")).isFalse();
        assertThat(cues.present("I changed my shirt. GraphSpindle is GPL-3.0 licensed.")).isFalse();
        assertThat(cues.present("> Actually, as the docs say: GraphSpindle is GPL-3.0 licensed.")).isFalse();
    }
}
