package io.graphspindle.ingestion;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code graphspindle.ingestion.*}.
 *
 * @param correctionCues words and phrases that mark an explicit correction (ADR-028); a cue starting
 *     with {@code re:} is a Java regular expression. Without any cue in the (redacted) content a
 *     {@code supersede_claim} is downgraded to a flagged contradiction.
 */
@ConfigurationProperties("graphspindle.ingestion")
public record IngestionProperties(List<String> correctionCues) {

    public static final List<String> DEFAULT_CORRECTION_CUES = List.of("actually", "correction", "corrected",
            "change of plan", "has changed", "have changed",
            "re:(?<![\\p{L}\\p{N}])chang(?:ed|ing)\\s+(?:[^.!?;\\n]{0,60}?\\s+)?(?:to|from|over)(?![\\p{L}\\p{N}])",
            "switched", "switching", "switch to", "no longer", "not anymore",
            "instead", "moved to", "moving to", "migrated", "now uses", "now use", "now using", "replaced",
            "replacing", "was wrong", "is wrong", "mistake", "rather than");

    public IngestionProperties {
        correctionCues = correctionCues == null || correctionCues.isEmpty() ? DEFAULT_CORRECTION_CUES
                : List.copyOf(correctionCues);
    }
}
