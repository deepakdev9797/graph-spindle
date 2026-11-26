package io.graphspindle.vocabulary;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders the vocabulary section of the LLM prompts from a {@link BrainVocabulary}. Deterministic:
 * the same vocabulary always renders the same text (the prompt cache key includes configVersion).
 * Engine-only enum values are never shown.
 */
public final class PromptVocabularyRenderer {

    public String render(BrainVocabulary vocabulary) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("VOCABULARY (profile ").append(vocabulary.profile()).append(")\n");
        sb.append("Use ONLY the entity types and predicates listed here. Anything else is rejected.\n\n");

        sb.append("Entity types:\n");
        for (EntityTypeDef t : vocabulary.entityTypes()) {
            sb.append("- ").append(t.name()).append(": ").append(t.description());
            if (!t.examples().isEmpty()) {
                sb.append(". Examples: ").append(String.join("; ", t.examples()));
            }
            sb.append('\n');
        }

        sb.append("\nClassification hints:\n");
        for (String hint : CoreVocabulary.CLASSIFICATION_HINTS) {
            if (appliesTo(hint, vocabulary)) {
                sb.append("- ").append(hint).append('\n');
            }
        }

        sb.append("\nRelation predicates (assert_claim with \"object\"; subject types -> object types; cardinality):\n");
        for (RelationPredicateDef p : vocabulary.relationPredicates()) {
            sb.append("- ").append(p.name()).append(": ").append(p.description()).append(". ")
                    .append(p.sourceTypes().describe()).append(" -> ").append(p.describeTargets())
                    .append("; ").append(p.cardinality());
            if (p.confidenceCap() != null) {
                sb.append("; confidence capped at ").append(p.confidenceCap());
            }
            sb.append('\n');
        }

        sb.append("\nAttribute predicates (assert_claim with a literal \"value\"):\n");
        for (AttributePredicateDef a : vocabulary.attributePredicates()) {
            sb.append("- ").append(a.name()).append(" on ").append(String.join(", ", a.entityTypes())).append(": ")
                    .append(a.description()).append(". ").append(describeValue(a));
            if (a.cardinality() == Cardinality.MANY) {
                sb.append("; MANY");
            }
            sb.append('\n');
        }

        sb.append("\nRules:\n");
        sb.append("- A subject and object must never be the same entity.\n");
        sb.append("- Cardinality ONE means at most one current value per subject.\n");
        sb.append("- Do not create entities for pronouns, dates, numbers or generic nouns.\n");
        return sb.toString();
    }

    private static boolean appliesTo(String hint, BrainVocabulary v) {
        Map<String, String> requires = new TreeMap<>(Map.of(
                "A programming language", "Technology",
                "A license", "Standard",
                "An Artifact", "Artifact",
                "A Decision", "Decision"));
        for (Map.Entry<String, String> e : requires.entrySet()) {
            if (hint.startsWith(e.getKey())) {
                return v.isEnabledType(e.getValue());
            }
        }
        return true;
    }

    static String describeValue(AttributePredicateDef a) {
        return switch (a.valueType()) {
            case ENUM -> "One of " + String.join(", ", a.llmEnumValues());
            case STRING -> "Text, at most " + (a.maxLength() == null ? 500 : a.maxLength()) + " characters";
            case PARTIAL_DATE -> "Date YYYY, YYYY-MM or YYYY-MM-DD";
            case BOOLEAN -> "true or false";
            case NUMBER -> "Decimal number" + range(a);
            case URL -> "URL starting with http://, https:// or file: (never fetched)";
        };
    }

    private static String range(AttributePredicateDef a) {
        if (a.min() == null && a.max() == null) {
            return "";
        }
        return " between " + (a.min() == null ? "-inf" : a.min().toPlainString()) + " and "
                + (a.max() == null ? "+inf" : a.max().toPlainString());
    }

    /** Convenience for tests and the vocabulary API. */
    public List<String> lines(BrainVocabulary vocabulary) {
        return render(vocabulary).lines().toList();
    }
}
