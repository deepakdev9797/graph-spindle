package io.graphspindle.graph.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds Lucene queries from user text. Every special character is backslash-escaped, so user
 * text is never passed to Lucene raw, and every query is scoped to one brain inside Lucene.
 */
public final class Lucene {

    private static final String SPECIAL = "+-&|!(){}[]^\"~*?:\\/";

    /** Bound on the number of words that take part in a query (the text itself is bounded by callers). */
    static final int MAX_WORDS = 32;

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

    /**
     * The {@code ftScope} token of a brain: the id with {@code -} replaced by {@code _}. Brain ids are
     * {@code [a-z0-9-]}, so this is one token for the standard analyzer (underscore joins words in
     * UAX#29) and distinct ids give distinct tokens. Must match {@code replace($brainId, '-', '_')}
     * in the statements that set the property.
     */
    public static String scopeToken(String brainId) {
        return brainId.replace('-', '_');
    }

    /**
     * Escaped search terms: the words of {@code text}, plus each pair of adjacent words and all words
     * joined without spaces, so "graph spindle" also matches "GraphSpindle" (SCHEMA §10.5). Empty when
     * the text has no words.
     */
    public static String terms(String text) {
        List<String> words = new ArrayList<>();
        for (String w : escape(text).split("\\s+")) {
            if (!w.isBlank() && words.size() < MAX_WORDS) {
                words.add(w);
            }
        }
        if (words.isEmpty()) {
            return "";
        }
        Set<String> terms = new LinkedHashSet<>(words);
        if (words.size() > 1) {
            for (int i = 0; i + 1 < words.size(); i++) {
                terms.add(words.get(i) + words.get(i + 1));
            }
            if (words.size() <= 8) {
                terms.add(String.join("", words));
            }
        }
        return String.join(" ", terms);
    }

    /**
     * A query that requires the brain's scope token and matches the terms in the given fields only
     * (never in {@code ftScope} itself): {@code +ftScope:dev +(name:(a b) description:(a b))}. Empty
     * when the text has no words.
     */
    public static String scoped(String brainId, String text, String... fields) {
        String t = terms(text);
        if (t.isEmpty()) {
            return "";
        }
        StringBuilder q = new StringBuilder("+ftScope:").append(scopeToken(brainId)).append(" +(");
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                q.append(' ');
            }
            q.append(fields[i]).append(":(").append(t).append(')');
        }
        return q.append(')').toString();
    }
}
