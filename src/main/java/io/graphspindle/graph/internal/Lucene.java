package io.graphspindle.graph.internal;

/** Escapes user text for Lucene query syntax: every special character is backslash-escaped. */
public final class Lucene {

    private static final String SPECIAL = "+-&|!(){}[]^\"~*?:\\/";

    private Lucene() {
    }

    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (SPECIAL.indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        // the words AND/OR/NOT are operators; lowercase them so they are plain terms
        return out.toString().replace("AND", "and").replace("OR", "or").replace("NOT", "not").strip();
    }
}
