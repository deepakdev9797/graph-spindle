package io.graphspindle.ingestion.validation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Checks that an evidence quote is a literal substring of the (redacted) ingestion content or of a
 * source summary, after whitespace and case normalization (ARCHITECTURE §5 {@code EVIDENCE_NOT_FOUND}).
 * Paraphrases are rejected. Typographic quotes and apostrophes are folded to ASCII so a model that
 * "fixes" quotes is not penalized.
 *
 * <p>A quote must also be substantial and relevant (DECISIONS ADR-021):
 * <ul>
 *   <li>at least {@value #MIN_EVIDENCE_CHARS} letters or digits, and either at least two words or at
 *       least {@value #MIN_SINGLE_WORD_CHARS} letters or digits. Text in a script written without
 *       spaces (Han, kana, Thai ...) needs {@value #MIN_NO_SPACE_SCRIPT_CHARS} letters instead;</li>
 *   <li>for {@link #supports(String, Collection)}, the quote or the sentence(s) containing it must
 *       mention one of the given terms (the claim's subject or object name or alias, or its value):
 *       the whole term, or for a multi-word name one of its significant words.</li>
 * </ul>
 */
public final class EvidenceChecker {

    /** Minimum letters or digits of a quote. */
    public static final int MIN_EVIDENCE_CHARS = 6;
    /** Minimum letters or digits of a single-word quote. */
    public static final int MIN_SINGLE_WORD_CHARS = 12;
    /** Minimum letters of a quote in a script written without spaces. */
    public static final int MIN_NO_SPACE_SCRIPT_CHARS = 4;

    private static final int MAX_OCCURRENCES = 64;
    private static final int MIN_SIGNIFICANT_WORD = 3;
    private static final Set<String> INSIGNIFICANT_WORDS = Set.of("the", "and", "for", "with", "from", "into",
            "onto", "use", "uses", "using", "used", "this", "that", "our", "its", "are", "was", "has", "have", "not",
            "but", "per", "via", "new", "old", "all", "any", "one", "two");

    private final List<String> haystacks;

    public EvidenceChecker(String content, List<String> sourceSummaries) {
        List<String> h = new ArrayList<>();
        h.add(normalize(content));
        if (sourceSummaries != null) {
            for (String s : sourceSummaries) {
                if (s != null && !s.isBlank()) {
                    h.add(normalize(s));
                }
            }
        }
        this.haystacks = List.copyOf(h);
    }

    /** The quote is substantial and occurs in the content or a source summary. */
    public boolean isSupported(String evidence) {
        String needle = normalize(evidence);
        if (!isSubstantial(needle)) {
            return false;
        }
        for (String h : haystacks) {
            if (h.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@link #isSupported(String)}, and the quote or a sentence containing it mentions one of
     * {@code terms}. An empty term list only applies the quote check.
     */
    public boolean supports(String evidence, Collection<String> terms) {
        String needle = normalize(evidence);
        if (!isSubstantial(needle)) {
            return false;
        }
        List<String> folded = new ArrayList<>();
        for (String t : terms) {
            String f = fold(normalize(t));
            if (!f.isEmpty()) {
                folded.add(f);
            }
        }
        for (String h : haystacks) {
            int at = h.indexOf(needle);
            for (int n = 0; at >= 0 && n < MAX_OCCURRENCES; n++) {
                if (folded.isEmpty()) {
                    return true;
                }
                String window = fold(h.substring(sentenceStart(h, at), sentenceEnd(h, at + needle.length())));
                for (String term : folded) {
                    if (mentions(window, term)) {
                        return true;
                    }
                }
                at = h.indexOf(needle, at + 1);
            }
        }
        return false;
    }

    static boolean isSubstantial(String needle) {
        int alnum = 0;
        int words = 0;
        boolean inWord = false;
        boolean noSpaceScript = false;
        for (int i = 0; i < needle.length(); ) {
            int cp = needle.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                alnum++;
                if (!inWord) {
                    words++;
                    inWord = true;
                }
                noSpaceScript |= isNoSpaceScript(cp);
            } else if (cp == ' ') {
                inWord = false;
            }
        }
        if (noSpaceScript && alnum >= MIN_NO_SPACE_SCRIPT_CHARS) {
            return true;
        }
        return alnum >= MIN_EVIDENCE_CHARS && (words >= 2 || alnum >= MIN_SINGLE_WORD_CHARS);
    }

    /** The term occurs in the window as a whole word, or one of its significant words does. */
    private static boolean mentions(String window, String term) {
        if (containsWord(window, term)) {
            return true;
        }
        if (term.indexOf(' ') < 0) {
            return false;
        }
        for (String word : term.split(" ")) {
            if (word.codePointCount(0, word.length()) >= MIN_SIGNIFICANT_WORD && !INSIGNIFICANT_WORDS.contains(word)
                    && containsWord(window, word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWord(String text, String word) {
        int at = text.indexOf(word);
        for (int n = 0; at >= 0 && n < MAX_OCCURRENCES; n++) {
            int end = at + word.length();
            boolean startOk = at == 0 || !joins(text.codePointBefore(at), word.codePointAt(0));
            boolean endOk = end == text.length() || !joins(word.codePointBefore(word.length()), text.codePointAt(end));
            if (startOk && endOk) {
                return true;
            }
            at = text.indexOf(word, at + 1);
        }
        return false;
    }

    /** True when two adjacent letters/digits form one word (scripts without spaces never do). */
    private static boolean joins(int a, int b) {
        return Character.isLetterOrDigit(a) && Character.isLetterOrDigit(b) && !isNoSpaceScript(a)
                && !isNoSpaceScript(b);
    }

    private static boolean isNoSpaceScript(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return switch (script) {
            case HAN, HIRAGANA, KATAKANA, THAI, LAO, KHMER, MYANMAR, TIBETAN -> true;
            default -> false;
        };
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

    /** {@code . ! ?} followed by a space or the end, or a full-width terminator. */
    private static boolean isTerminatorAt(String h, int i) {
        char c = h.charAt(i);
        if (c == '。' || c == '！' || c == '？') {
            return true;
        }
        return (c == '.' || c == '!' || c == '?') && (i + 1 == h.length() || h.charAt(i + 1) == ' ');
    }

    /** {@link #normalize} plus diacritics removed, matching {@code norm()}'s folding of names. */
    private static String fold(String normalized) {
        return io.graphspindle.common.Normalizer.removeDiacritics(normalized);
    }

    /** NFKC, format characters removed, quotes folded, lowercase, whitespace collapsed. */
    static String normalize(String s) {
        return mapped(s).text();
    }

    /**
     * Where the evidence occurs in {@code content} under exactly the normalization of
     * {@link #isSupported}: {@code [start, end)} offsets into the original content (at most 64
     * occurrences). Lets callers look at the original text around an accepted quote (ADR-034).
     */
    public static List<int[]> locate(String content, String evidence) {
        String needle = normalize(evidence);
        List<int[]> out = new ArrayList<>();
        if (needle.isEmpty() || content == null) {
            return out;
        }
        Mapped h = mapped(content);
        for (int idx = h.text().indexOf(needle); idx >= 0 && out.size() < MAX_OCCURRENCES;
                idx = h.text().indexOf(needle, idx + 1)) {
            out.add(new int[] {h.start()[idx], h.end()[idx + needle.length() - 1]});
        }
        return out;
    }

    /** Normalized text with, per normalized char, the original {@code [start, end)} it came from. */
    record Mapped(String text, int[] start, int[] end) {
    }

    static Mapped mapped(String s) {
        if (s == null) {
            return new Mapped("", new int[0], new int[0]);
        }
        StringBuilder out = new StringBuilder(s.length());
        int[] starts = new int[s.length() * 2 + 4];
        int[] ends = new int[starts.length];
        boolean space = false;
        int n = s.length();
        for (int i = 0; i < n; ) {
            int chunkStart = i;
            i += Character.charCount(s.codePointAt(i));
            // a base character with its combining marks is normalized as one unit (NFKC composes them)
            while (i < n) {
                int next = s.codePointAt(i);
                int t = Character.getType(next);
                if (t != Character.NON_SPACING_MARK && t != Character.ENCLOSING_MARK
                        && t != Character.COMBINING_SPACING_MARK) {
                    break;
                }
                i += Character.charCount(next);
            }
            String chunk = Normalizer.normalize(s.substring(chunkStart, i), Normalizer.Form.NFKC);
            for (int j = 0; j < chunk.length(); ) {
                int cp = chunk.codePointAt(j);
                j += Character.charCount(cp);
                if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                    space = true;
                    continue;
                }
                int type = Character.getType(cp);
                if (type == Character.FORMAT || type == Character.CONTROL) {
                    continue;
                }
                cp = switch (cp) {
                    case '‘', '’', '‚', '‛', '′', '`' -> '\'';
                    case '“', '”', '„', '‟', '″', '«', '»' -> '"';
                    case '–', '—', '−' -> '-';
                    default -> cp;
                };
                String lower = new String(Character.toChars(cp)).toLowerCase(Locale.ROOT);
                if (out.length() + lower.length() + 1 >= starts.length) {
                    starts = java.util.Arrays.copyOf(starts, starts.length * 2 + lower.length() + 2);
                    ends = java.util.Arrays.copyOf(ends, starts.length);
                }
                if (space && !out.isEmpty()) {
                    starts[out.length()] = chunkStart;
                    ends[out.length()] = chunkStart;
                    out.append(' ');
                }
                space = false;
                for (int k = 0; k < lower.length(); k++) {
                    starts[out.length()] = chunkStart;
                    ends[out.length()] = i;
                    out.append(lower.charAt(k));
                }
            }
        }
        return new Mapped(out.toString(), starts, ends);
    }
}
