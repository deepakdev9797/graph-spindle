package io.graphspindle.ingestion.validation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks that an evidence quote is a literal substring of the (redacted) ingestion content or of a
 * source summary, after whitespace and case normalization (ARCHITECTURE §5 {@code EVIDENCE_NOT_FOUND}).
 * Paraphrases are rejected. Typographic quotes and apostrophes are folded to ASCII so a model that
 * "fixes" quotes is not penalized, and very short quotes are refused because they prove nothing.
 */
public final class EvidenceChecker {

    /** Minimum normalized evidence length, in characters. */
    public static final int MIN_EVIDENCE_CHARS = 3;

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

    public boolean isSupported(String evidence) {
        String needle = normalize(evidence);
        if (needle.length() < MIN_EVIDENCE_CHARS) {
            return false;
        }
        for (String h : haystacks) {
            if (h.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** NFKC, format characters removed, quotes folded, lowercase, whitespace collapsed. */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(n.length());
        boolean space = false;
        for (int i = 0; i < n.length(); ) {
            int cp = n.codePointAt(i);
            i += Character.charCount(cp);
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
            if (space && !out.isEmpty()) {
                out.append(' ');
            }
            space = false;
            out.appendCodePoint(cp);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
