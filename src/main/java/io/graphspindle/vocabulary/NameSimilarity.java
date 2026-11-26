package io.graphspindle.vocabulary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The similarity guardrail for new vocabulary names (ADR-025). A candidate is similar to a known
 * term when, after normalization (lower case, letters and digits only), the two are equal, equal
 * after reducing plurals to the singular, or (when the candidate has at least 5 characters) within
 * Levenshtein distance 2.
 */
public final class NameSimilarity {

    static final int MIN_FUZZY_LENGTH = 5;
    static final int MAX_DISTANCE = 2;

    /** What a known term refers to. */
    public enum Kind {
        NODE_TYPE,
        NODE_TYPE_SYNONYM,
        PROPERTY,
        PROPERTY_SYNONYM,
        RELATIONSHIP_TYPE,
        RELATIONSHIP_TYPE_SYNONYM
    }

    /**
     * A known name.
     *
     * @param term the name or synonym as written
     * @param kind what it is
     * @param target what to use instead: a type name, {@code Type.property}, or a relationship type
     */
    public record Term(String term, Kind kind, String target) {
    }

    /**
     * A similarity hit.
     *
     * @param term the known term that matched
     * @param kind what the term is
     * @param target what to use instead
     * @param reason {@code SAME_NAME}, {@code SINGULAR_PLURAL} or {@code EDIT_DISTANCE}
     */
    public record Match(String term, Kind kind, String target, String reason) {

        /** A human readable hint naming the similar definition. */
        public String hint(String candidate) {
            return switch (kind) {
                case NODE_TYPE -> "'" + candidate + "' is similar to the node type '" + target + "'; use " + target
                        + " instead";
                case NODE_TYPE_SYNONYM -> "'" + candidate + "' is similar to '" + term + "', a synonym of the node type '"
                        + target + "'; use " + target + " instead";
                case PROPERTY, PROPERTY_SYNONYM -> "'" + candidate + "' is similar to '" + term + "', which is modelled"
                        + " as the property " + target + "; record it as that property instead of a new type";
                case RELATIONSHIP_TYPE -> "'" + candidate + "' is similar to the relationship type '" + target
                        + "'; use " + target + " instead";
                case RELATIONSHIP_TYPE_SYNONYM -> "'" + candidate + "' is similar to '" + term
                        + "', a synonym of the relationship type '" + target + "'; use " + target + " instead";
            };
        }
    }

    private NameSimilarity() {
    }

    /** The first known term similar to the candidate, preferring exact over plural over fuzzy matches. */
    public static Optional<Match> findSimilar(String candidate, Collection<Term> known) {
        String c = normalize(candidate);
        if (c.isEmpty()) {
            return Optional.empty();
        }
        String cs = singular(c);
        List<Match> plural = new ArrayList<>();
        List<Match> fuzzy = new ArrayList<>();
        for (Term t : known) {
            String k = normalize(t.term());
            if (k.isEmpty()) {
                continue;
            }
            if (k.equals(c)) {
                return Optional.of(new Match(t.term(), t.kind(), t.target(), "SAME_NAME"));
            }
            String ks = singular(k);
            if (ks.equals(cs)) {
                plural.add(new Match(t.term(), t.kind(), t.target(), "SINGULAR_PLURAL"));
            } else if (c.length() >= MIN_FUZZY_LENGTH && Math.abs(cs.length() - ks.length()) <= MAX_DISTANCE
                    && levenshtein(cs, ks, MAX_DISTANCE) <= MAX_DISTANCE) {
                fuzzy.add(new Match(t.term(), t.kind(), t.target(), "EDIT_DISTANCE"));
            }
        }
        if (!plural.isEmpty()) {
            return Optional.of(plural.get(0));
        }
        return fuzzy.stream().findFirst();
    }

    /** Lower case, letters and digits only ({@code MEMBER_OF} → {@code memberof}, {@code "Job Title"} → {@code jobtitle}). */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        s.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    /** A crude English singular: {@code ies → y}, {@code (s|x|z|ch|sh)es → $1}, trailing {@code s} (not {@code ss}). */
    static String singular(String s) {
        if (s.length() > 4 && s.endsWith("ies")) {
            return s.substring(0, s.length() - 3) + "y";
        }
        if (s.length() > 4 && (s.endsWith("ses") || s.endsWith("xes") || s.endsWith("zes") || s.endsWith("ches")
                || s.endsWith("shes"))) {
            return s.substring(0, s.length() - 2);
        }
        if (s.length() > 3 && s.endsWith("s") && !s.endsWith("ss")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Levenshtein distance, stopping early once it exceeds {@code limit}. */
    static int levenshtein(String a, String b, int limit) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > limit) {
                return limit + 1;
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
