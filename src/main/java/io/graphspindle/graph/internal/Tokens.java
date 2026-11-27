package io.graphspindle.graph.internal;

import io.graphspindle.vocabulary.BrainVocabulary;
import java.util.Set;

/**
 * Whitelist for dynamic labels and relationship types (SCHEMA I5, §12). A token is accepted only if
 * it is in the brain vocabulary <em>and</em> is a plain identifier that is not a system name, so no
 * request or model text can ever become a label or a relationship type.
 */
public final class Tokens {

    private static final Set<String> SYSTEM = Set.of("Entity", "Alias", "Claim", "Ingestion", "SourceRef", "Conflict",
            "Brain", "ApiKey", "ApiKeyGrant", "System", "Internal", "HAS_ALIAS", "SUBJECT", "OBJECT", "EVIDENCED_BY", "CITES",
            "SUPERSEDES", "MENTIONED_IN", "SUBMITTED_IN", "DESCRIBES", "MERGED_INTO", "INVOLVES", "RAISED_BY");

    private Tokens() {
    }

    public static String entityLabel(BrainVocabulary vocabulary, String type) {
        if (!identifier(type) || !vocabulary.isEnabledType(type)) {
            throw new IllegalArgumentException("entity type is not in the brain vocabulary");
        }
        return type;
    }

    public static String relationType(BrainVocabulary vocabulary, String predicate) {
        if (!identifier(predicate) || vocabulary.relation(predicate).isEmpty()) {
            throw new IllegalArgumentException("relation predicate is not in the brain vocabulary");
        }
        return predicate;
    }

    public static String attributeProperty(BrainVocabulary vocabulary, String predicate) {
        if (!identifier(predicate) || !vocabulary.hasAttributeName(predicate)) {
            throw new IllegalArgumentException("attribute predicate is not in the brain vocabulary");
        }
        return "attr_" + predicate;
    }

    /**
     * The predicate of a <em>stored</em> claim, for projection and removal paths: the vocabulary may
     * have changed since the claim was written (a predicate disabled or removed), so only the
     * identifier rules apply. Never use this for request or model text.
     */
    public static String storedPredicate(String predicate) {
        if (!identifier(predicate)) {
            throw new IllegalArgumentException("stored predicate is not a plain identifier");
        }
        return predicate;
    }

    /** A plain identifier that is not a system label or relationship type. */
    public static boolean identifier(String token) {
        if (token == null || token.isEmpty() || token.length() > 40 || SYSTEM.contains(token)
                || token.startsWith("_")) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok || (i == 0 && !Character.isLetter(c))) {
                return false;
            }
        }
        return true;
    }
}
