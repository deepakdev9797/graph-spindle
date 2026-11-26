package io.graphspindle.ingestion.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides whether the input contains explicit correction language (ADR-028). Models label plain
 * contradictions as explicit corrections, so the engine checks the redacted input itself: each cue is
 * a whole word or phrase (case-insensitive, any whitespace between words) or, prefixed with
 * {@code re:}, a regular expression. The patterns "not X but Y" and "moved/switched/migrated X to Y" are always recognised.
 */
public final class CorrectionCues {

    /** "not Apache but MIT", "not GPL, but MIT". */
    static final Pattern NOT_X_BUT_Y = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])not\\s+[^.!?;\\n]{1,60}?\\s*,?\\s+but"
            + "(?![\\p{L}\\p{N}])");

    /** "moved Ferrite to SQLite", "switched the project over to MIT". */
    static final Pattern MOVED_X_TO_Y = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(?:moved|switched|migrated)\\s+"
            + "[^.!?;\\n]{1,60}?\\s+to(?![\\p{L}\\p{N}])");

    private final List<Pattern> patterns;

    public CorrectionCues(List<String> cues) {
        List<Pattern> p = new ArrayList<>();
        for (String cue : cues) {
            if (cue == null || cue.isBlank()) {
                continue;
            }
            String c = cue.strip();
            if (c.startsWith("re:")) {
                p.add(Pattern.compile(c.substring(3), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            } else {
                StringBuilder re = new StringBuilder("(?iu)(?<![\\p{L}\\p{N}])");
                String[] words = c.split("\\s+");
                for (int i = 0; i < words.length; i++) {
                    re.append(i == 0 ? "" : "\\s+").append(Pattern.quote(words[i]));
                }
                // a cue ending in punctuation ("update:") needs no word boundary after it
                re.append(Character.isLetterOrDigit(c.charAt(c.length() - 1)) ? "(?![\\p{L}\\p{N}])" : "");
                p.add(Pattern.compile(re.toString()));
            }
        }
        p.add(NOT_X_BUT_Y);
        p.add(MOVED_X_TO_Y);
        this.patterns = List.copyOf(p);
    }

    /** True when {@code text} contains a correction cue. Linear patterns only (no nested quantifiers). */
    public boolean present(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String t = text.replace('’', '\'');
        return patterns.stream().anyMatch(p -> p.matcher(t).find());
    }
}
