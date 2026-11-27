package io.graphspindle.ingestion.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether the input contains explicit correction language (ADR-028, ADR-031). Models label
 * plain contradictions as explicit corrections, so the engine checks the redacted input itself: each
 * cue is a whole word or phrase (case-insensitive, any whitespace between words) or, prefixed with
 * {@code re:}, a regular expression. The patterns "not X but Y" and "moved/switched/migrated X to Y"
 * are always recognised.
 *
 * <p>Only the speaker's own words count (ADR-031): lines starting with {@code >} and text in double
 * quotes are ignored, "not only/just … but" is not a correction, and "moved X to Y" does not count
 * when Y is a time ("moved the standup to Friday"). {@link #presentNear} additionally requires the cue
 * in the sentence of the operation's evidence quote, or in a short sentence right before it
 * ("Correction. GraphSpindle is MIT.").
 */
public final class CorrectionCues {

    /** A preceding sentence up to this length may carry the cue for the next one. */
    static final int MAX_LEAD_SENTENCE_CHARS = 60;
    private static final int MAX_QUOTED_SPAN = 400;

    /** "not Apache but MIT", "not GPL, but MIT"; not "not only fast but ...". */
    static final Pattern NOT_X_BUT_Y = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])not\\s+"
            + "(?!(?:only|just|merely|simply|solely)(?![\\p{L}\\p{N}]))[^.!?;\\n]{1,60}?\\s*,?\\s+but"
            + "(?![\\p{L}\\p{N}])");

    private static final String TIME_WORDS = "(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|today"
            + "|tomorrow|tonight|yesterday|next|later|earlier|morning|afternoon|evening|noon|midnight|the\\s+next"
            + "|the\\s+following|the\\s+end|the\\s+week|the\\s+weekend|the\\s+morning|the\\s+afternoon|the\\s+evening"
            + "|january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar"
            + "|apr|jun|jul|aug|sep|sept|oct|nov|dec|q[1-4])";

    /** "moved Ferrite to SQLite", "switched the project over to MIT"; not "moved the standup to Friday". */
    static final Pattern MOVED_X_TO_Y = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(?:moved|switched|migrated)\\s+"
            + "[^.!?;\\n]{1,60}?\\s+to\\s+(?!" + TIME_WORDS + "(?![\\p{L}])|\\d)(?=[\\p{L}\\p{N}])");

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

    /**
     * True when the speaker's own words in {@code text} contain a correction cue anywhere. Linear
     * patterns only (no nested quantifiers).
     */
    public boolean present(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return matches(mask(fold(text)));
    }

    /**
     * True when a correction cue occurs in the speaker's own words in the sentence(s) containing an
     * occurrence of {@code evidence} in {@code text}, or in a short sentence directly before it. An
     * evidence quote that does not occur in the text (it may come from a source summary) falls back to
     * {@link #present}.
     */
    public boolean presentNear(String text, String evidence) {
        if (text == null || text.isEmpty() || evidence == null || evidence.isBlank()) {
            return false;
        }
        // located with EvidenceChecker's own normalization (NFKC, dashes, quotes, format characters), so
        // every quote the validator accepts is found; an unlocated quote never counts (ADR-034)
        String masked = mask(fold(text));
        for (int[] at : io.graphspindle.ingestion.validation.EvidenceChecker.locate(text, evidence)) {
            // only the sentence holding the end of the quote (a quote spanning sentences does not widen it)
            int last = Math.max(at[0], at[1] - 1);
            int start = sentenceStart(masked, last);
            int end = sentenceEnd(masked, last);
            if (start > 0) {
                int leadStart = sentenceStart(masked, start - 1);
                if (start - leadStart <= MAX_LEAD_SENTENCE_CHARS) {
                    start = leadStart;
                }
            }
            if (matches(masked.substring(start, end))) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(String t) {
        return patterns.stream().anyMatch(p -> p.matcher(t).find());
    }

    private static String fold(String text) {
        return text.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"');
    }

    /** Blanks (keeping offsets) {@code >} quote lines and double-quoted spans: not the speaker's words. */
    static String mask(String text) {
        char[] c = text.toCharArray();
        int lineStart = 0;
        for (int i = 0; i <= c.length; i++) {
            if (i == c.length || c[i] == '\n') {
                int j = lineStart;
                while (j < i && (c[j] == ' ' || c[j] == '\t')) {
                    j++;
                }
                if (j < i && c[j] == '>') {
                    for (int k = lineStart; k < i; k++) {
                        c[k] = ' ';
                    }
                }
                lineStart = i + 1;
            }
        }
        for (int i = 0; i < c.length; i++) {
            if (c[i] == '"') {
                int close = -1;
                for (int k = i + 1; k < c.length && k - i <= MAX_QUOTED_SPAN && c[k] != '\n'; k++) {
                    if (c[k] == '"') {
                        close = k;
                        break;
                    }
                }
                if (close < 0) {
                    continue;
                }
                for (int k = i; k <= close; k++) {
                    c[k] = ' ';
                }
                i = close;
            }
        }
        return new String(c);
    }

    private static int sentenceStart(String h, int from) {
        for (int i = from - 1; i >= 0; i--) {
            if (isTerminatorAt(h, i)) {
                return i + 1;
            }
        }
        return 0;
    }

    private static int sentenceEnd(String h, int from) {
        for (int i = Math.max(from, 0); i < h.length(); i++) {
            if (isTerminatorAt(h, i)) {
                return i + 1;
            }
        }
        return h.length();
    }

    /** {@code . ! ?} followed by whitespace or the end, a full-width terminator, or a line break. */
    private static boolean isTerminatorAt(String h, int i) {
        char c = h.charAt(i);
        if (c == '\n' || c == '。' || c == '！' || c == '？') {
            return true;
        }
        return (c == '.' || c == '!' || c == '?') && (i + 1 == h.length() || Character.isWhitespace(h.charAt(i + 1)));
    }
}
