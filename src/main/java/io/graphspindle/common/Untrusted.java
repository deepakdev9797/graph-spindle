package io.graphspindle.common;

/** Helpers for quoting untrusted text (model output, ingested content) in messages and logs. */
public final class Untrusted {

    public static final int PREVIEW_CHARS = 60;

    private Untrusted() {
    }

    /** A short, single-line preview with control characters removed, safe for error messages. */
    public static String preview(String s) {
        if (s == null) {
            return "null";
        }
        String bounded = s.length() > PREVIEW_CHARS * 4 ? s.substring(0, PREVIEW_CHARS * 4) : s;
        String clean = Normalizer.normalizeWhitespace(bounded);
        String cut = Normalizer.truncate(clean, PREVIEW_CHARS);
        return cut.length() < clean.length() || bounded.length() < s.length() ? cut + "…" : cut;
    }
}
