package io.graphspindle.resolution;

import io.graphspindle.common.Normalizer;
import java.util.Collection;

/**
 * Deterministic name similarity for entity resolution (ADR-030). Names are compared on their
 * {@link Normalizer#compact} keys, so case, spacing, punctuation and a leading "The" never matter.
 * The score is 1.0 for equal keys and otherwise {@code 1 - levenshtein / longer length}; keys
 * shorter than {@value #MIN_FUZZY_LENGTH} characters only match exactly (for "Go", "C#", "R" a
 * one-letter edit is a different name). Pure; no I/O.
 */
public final class NameMatch {

    static final int MIN_FUZZY_LENGTH = 4;
    /** Inputs are names (≤ 200 code points); longer keys are compared by equality only. */
    static final int MAX_FUZZY_LENGTH = 120;

    private NameMatch() {
    }

    /** Similarity of two names in [0, 1]. */
    public static double similarity(String a, String b) {
        return compactSimilarity(Normalizer.compact(a), Normalizer.compact(b));
    }

    /** The best similarity of {@code name} to any of {@code others}. */
    public static double best(String name, Collection<String> others) {
        String ca = Normalizer.compact(name);
        double best = 0;
        for (String o : others) {
            best = Math.max(best, compactSimilarity(ca, Normalizer.compact(o)));
            if (best >= 1.0) {
                break;
            }
        }
        return best;
    }

    static double compactSimilarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        int shorter = Math.min(a.length(), b.length());
        int longer = Math.max(a.length(), b.length());
        if (shorter < MIN_FUZZY_LENGTH || longer > MAX_FUZZY_LENGTH) {
            return 0;
        }
        return 1.0 - (double) levenshtein(a, b) / longer;
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
