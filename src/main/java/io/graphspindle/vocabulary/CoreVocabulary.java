package io.graphspindle.vocabulary;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The bundled core vocabulary profile {@code core-v1} (SCHEMA §6). The definition lives in
 * {@code src/main/resources/vocabulary/core-v1.yml} and is loaded and validated by
 * {@link VocabularyDefinitionLoader} (ADR-025); this class exposes the bundled profile as constants
 * and holds the engine's reserved names, which are not vocabulary.
 *
 * <p>The running application uses the {@link CoreProfile} bean, which may carry an operator override
 * ({@code graphspindle.vocabulary.location}); these constants always describe the bundled file.
 */
public final class CoreVocabulary {

    public static final String PROFILE = "core-v1";

    /** Type compatibility groups used by the bundled profile (SCHEMA §6.2). */
    public static final String GROUP_TECHNICAL = "TECHNICAL";
    public static final String GROUP_WORK = "WORK";
    public static final String GROUP_ACTOR = "ACTOR";
    public static final String GROUP_OBJECTIVE = "OBJECTIVE";

    /** Node labels used by the engine (SCHEMA §5.2) plus additional reserved names. */
    public static final Set<String> SYSTEM_LABELS =
            Set.of("Entity", "Alias", "Claim", "Ingestion", "SourceRef", "Conflict", "Brain", "ApiKey");

    public static final Set<String> RESERVED_LABELS;

    /** System relationship types (SCHEMA §5.3); custom predicates must not reuse them. */
    public static final Set<String> SYSTEM_RELATIONSHIP_TYPES = Set.of("HAS_ALIAS", "SUBJECT", "OBJECT",
            "EVIDENCED_BY", "CITES", "SUPERSEDES", "MENTIONED_IN", "SUBMITTED_IN", "DESCRIBES", "MERGED_INTO",
            "INVOLVES", "RAISED_BY");

    public static final String PERSON = "Person";

    /** Decision status value that only the engine may set (consequence of REPLACES). */
    public static final String DECISION_STATUS_SUPERSEDED = "SUPERSEDED";

    static {
        java.util.HashSet<String> reserved = new java.util.HashSet<>(SYSTEM_LABELS);
        reserved.add("System");
        reserved.add("Internal");
        RESERVED_LABELS = Set.copyOf(reserved);
    }

    /** The bundled profile, loaded from {@code classpath:vocabulary/core-v1.yml}. */
    public static final CoreProfile DEFAULT = VocabularyDefinitionLoader.bundled();

    public static final List<EntityTypeDef> ENTITY_TYPES = DEFAULT.entityTypes();
    public static final List<RelationPredicateDef> RELATION_PREDICATES = DEFAULT.relationPredicates();
    public static final List<AttributePredicateDef> ATTRIBUTE_PREDICATES = DEFAULT.attributePredicates();
    public static final Map<String, Set<String>> COMPATIBILITY_GROUPS = DEFAULT.compatibilityGroups();

    /** Classification hints the prompt includes (SCHEMA §6.1), as text. */
    public static final List<String> CLASSIFICATION_HINTS =
            DEFAULT.classificationHints().stream().map(ClassificationHint::text).toList();

    private CoreVocabulary() {
    }

    /** True for a reserved label: system labels, {@code System}, {@code Internal}, or anything starting with {@code _}. */
    public static boolean isReservedLabel(String name) {
        if (name == null || name.startsWith("_")) {
            return true;
        }
        for (String r : RESERVED_LABELS) {
            if (r.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isCoreEntityType(String name) {
        return DEFAULT.isCoreEntityType(name);
    }

    public static boolean isCoreRelation(String name) {
        return DEFAULT.isCoreRelation(name);
    }

    public static boolean isCoreAttributeName(String name) {
        return DEFAULT.isCoreAttributeName(name);
    }

    /** Distinct attribute predicate names in the core profile. */
    public static Set<String> attributeNames() {
        return DEFAULT.attributeNames();
    }
}
