package io.graphspindle.vocabulary;

import static io.graphspindle.vocabulary.Cardinality.MANY;
import static io.graphspindle.vocabulary.Cardinality.ONE;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The core vocabulary profile {@code core-v1}, exactly as SCHEMA §6 defines it, as data.
 * The prompt, the output JSON schema, the validator and the vocabulary API are all rendered from
 * these records.
 */
public final class CoreVocabulary {

    public static final String PROFILE = "core-v1";

    /** Type compatibility groups (SCHEMA §6.2). */
    public static final String GROUP_TECHNICAL = "TECHNICAL";
    public static final String GROUP_WORK = "WORK";
    public static final String GROUP_ACTOR = "ACTOR";
    public static final String GROUP_OBJECTIVE = "OBJECTIVE";

    public static final Map<String, Set<String>> COMPATIBILITY_GROUPS = Map.of(
            GROUP_TECHNICAL, Set.of("Technology", "Standard", "Concept", "Skill"),
            GROUP_WORK, Set.of("Project", "Artifact"),
            GROUP_ACTOR, Set.of("Person", "Agent"),
            GROUP_OBJECTIVE, Set.of("Task", "Goal"));

    /** Node labels used by the engine (SCHEMA §5.2) plus additional reserved names. */
    public static final Set<String> SYSTEM_LABELS =
            Set.of("Entity", "Alias", "Claim", "Ingestion", "SourceRef", "Conflict", "Brain", "ApiKey");

    public static final Set<String> RESERVED_LABELS;

    /** System relationship types (SCHEMA §5.3); custom predicates must not reuse them. */
    public static final Set<String> SYSTEM_RELATIONSHIP_TYPES = Set.of("HAS_ALIAS", "SUBJECT", "OBJECT",
            "EVIDENCED_BY", "CITES", "SUPERSEDES", "MENTIONED_IN", "SUBMITTED_IN", "DESCRIBES", "MERGED_INTO",
            "INVOLVES", "RAISED_BY");

    /** Classification hints the prompt must include (SCHEMA §6.1). */
    public static final List<String> CLASSIFICATION_HINTS = List.of(
            "A programming language is a Technology with category = LANGUAGE.",
            "A license is a Standard with category = LICENSE.",
            "An Artifact exists only if it is a specific item. Otherwise use Concept.",
            "A Decision must have a chosen option. Otherwise record a Task or a Goal.",
            "Do not create entities for pronouns, dates, numbers, generic nouns (\"the code\", \"a database\"), "
                    + "or one-off conversational items.");

    public static final String PERSON = "Person";

    public static final List<EntityTypeDef> ENTITY_TYPES = List.of(
            type("Person", "A specific human being", GROUP_ACTOR, "Alice Chen", "the speaker (\"I\")"),
            type("Agent", "A specific software agent, bot or robot that acts", GROUP_ACTOR,
                    "Claude Code", "warehouse robot R2"),
            type("Organization", "A company, team, community, institution or group", null,
                    "Acme Corp", "Apache Software Foundation"),
            type("Project", "A named, bounded effort with a goal (software or not)", GROUP_WORK,
                    "GraphSpindle", "kitchen renovation"),
            type("Technology", "A language, framework, library, database, tool, platform or service",
                    GROUP_TECHNICAL, "Java", "Neo4j", "Spring AI"),
            type("Standard", "A license, specification, protocol, format or regulation", GROUP_TECHNICAL,
                    "Apache License 2.0", "MCP", "GDPR"),
            type("Artifact", "A concrete work product: repository, document, dataset, model, design", GROUP_WORK,
                    "graph-spindle repo", "Q3 report"),
            type("Concept", "An abstract idea, topic, method or field", GROUP_TECHNICAL,
                    "knowledge graph", "entity resolution"),
            type("Decision", "A choice that was made or proposed, phrased as \"Use X for Y\"", null,
                    "Use Apache 2.0 for GraphSpindle"),
            type("Task", "A concrete piece of work to be done", GROUP_OBJECTIVE, "write Neo4j integration tests"),
            type("Goal", "A desired outcome or objective", GROUP_OBJECTIVE, "ship MVP by March"),
            type("Event", "Something that happened or will happen at a time", null,
                    "v0.1 release", "design review 2026-11-20"),
            type("Place", "A geographic or physical location", null, "Berlin", "office lab 3"),
            type("Preference", "A stated, durable like, dislike or working preference of a person or agent", null,
                    "prefers concise answers"),
            type("Skill", "A capability or expertise a person or agent has", GROUP_TECHNICAL,
                    "Cypher", "technical writing"));

    private static final TypeSet ACTORS_AND_ORGS = TypeSet.of("Person", "Agent", "Organization");
    private static final TypeSet CHOICE_TARGETS = TypeSet.of("Technology", "Standard", "Artifact", "Concept",
            "Organization", "Place", "Person", "Agent", "Project");

    public static final List<RelationPredicateDef> RELATION_PREDICATES = List.of(
            rel("WORKS_ON", "is actively working on", ACTORS_AND_ORGS,
                    TypeSet.of("Project", "Task", "Goal", "Artifact"), MANY),
            rel("MEMBER_OF", "belongs to, or is employed by", ACTORS_AND_ORGS,
                    TypeSet.of("Organization", "Project"), MANY),
            rel("CREATED", "authored, founded or built", ACTORS_AND_ORGS,
                    TypeSet.of("Project", "Technology", "Standard", "Artifact", "Concept"), MANY),
            rel("MAINTAINS", "owns or maintains", ACTORS_AND_ORGS,
                    TypeSet.of("Project", "Technology", "Artifact"), MANY),
            rel("USES", "uses as a component or tool",
                    TypeSet.of("Project", "Artifact", "Technology", "Person", "Agent", "Organization", "Task"),
                    TypeSet.of("Technology", "Standard", "Artifact"), MANY),
            rel("WRITTEN_IN", "implemented in a language", TypeSet.of("Project", "Artifact", "Technology"),
                    TypeSet.of("Technology"), MANY),
            rel("DEPENDS_ON", "requires to function or proceed",
                    TypeSet.of("Project", "Artifact", "Technology", "Task", "Goal"),
                    TypeSet.of("Project", "Artifact", "Technology", "Task", "Goal"), MANY),
            new RelationPredicateDef("PART_OF", "is a component or sub-part of",
                    TypeSet.of("Project", "Artifact", "Technology", "Organization", "Task", "Goal", "Event",
                            "Place", "Concept"),
                    TypeSet.of(), true, Map.of("Task", Set.of("Project"), "Artifact", Set.of("Project")),
                    MANY, null, true),
            rel("LICENSED_UNDER", "distributed under the license", TypeSet.of("Project", "Artifact", "Technology"),
                    TypeSet.of("Standard"), ONE),
            rel("IMPLEMENTS", "implements the spec or idea", TypeSet.of("Project", "Artifact", "Technology"),
                    TypeSet.of("Standard", "Concept"), MANY),
            rel("ALTERNATIVE_TO", "can substitute for", TypeSet.of("Technology", "Standard", "Project"),
                    TypeSet.of("Technology", "Standard", "Project"), MANY),
            rel("IS_A", "is a kind of", TypeSet.of("Technology", "Standard", "Concept", "Skill"),
                    TypeSet.of("Concept"), MANY),
            rel("ABOUT", "has the topic or subject", TypeSet.of("Artifact", "Event", "Decision", "Goal", "Task"),
                    TypeSet.any(), MANY),
            rel("DECIDED", "made or approved the decision", ACTORS_AND_ORGS, TypeSet.of("Decision"), MANY),
            rel("APPLIES_TO", "is scoped to", TypeSet.of("Decision", "Preference"),
                    TypeSet.of("Project", "Organization", "Artifact", "Task", "Goal", "Person", "Agent"), MANY),
            rel("CHOOSES", "the selected option", TypeSet.of("Decision"), CHOICE_TARGETS, ONE),
            rel("REJECTS", "a considered and rejected option", TypeSet.of("Decision"), CHOICE_TARGETS, MANY),
            rel("REPLACES", "the new decision replaces the old one", TypeSet.of("Decision"),
                    TypeSet.of("Decision"), ONE),
            rel("ASSIGNED_TO", "is to be done by", TypeSet.of("Task"), TypeSet.of("Person", "Agent"), MANY),
            rel("BLOCKS", "prevents progress on", TypeSet.of("Task"), TypeSet.of("Task", "Goal"), MANY),
            rel("CONTRIBUTES_TO", "advances the goal", TypeSet.of("Project", "Task", "Decision", "Event"),
                    TypeSet.of("Goal"), MANY),
            rel("PARTICIPATED_IN", "took part in", ACTORS_AND_ORGS, TypeSet.of("Event"), MANY),
            rel("LOCATED_IN", "located in or took place at",
                    TypeSet.of("Person", "Organization", "Event", "Place", "Artifact"), TypeSet.of("Place"), ONE),
            rel("KNOWS", "knows or works with", TypeSet.of("Person", "Agent"), TypeSet.of("Person", "Agent"), MANY),
            rel("HAS_PREFERENCE", "holds the preference", TypeSet.of("Person", "Agent"), TypeSet.of("Preference"),
                    MANY),
            rel("HAS_SKILL", "is skilled in", TypeSet.of("Person", "Agent"), TypeSet.of("Skill", "Technology"), MANY),
            rel("INTERESTED_IN", "is interested in", ACTORS_AND_ORGS, TypeSet.any(), MANY),
            new RelationPredicateDef("RELATED_TO", "fallback only, when nothing more specific fits", TypeSet.any(),
                    TypeSet.any(), false, Map.of(), MANY, 0.6, true));

    /** Decision status value that only the engine may set (consequence of REPLACES). */
    public static final String DECISION_STATUS_SUPERSEDED = "SUPERSEDED";

    public static final List<AttributePredicateDef> ATTRIBUTE_PREDICATES = List.of(
            enumAttr("status", "lifecycle status of the project", Set.of("Project"),
                    List.of("PLANNED", "ACTIVE", "PAUSED", "COMPLETED", "ABANDONED"), Set.of()),
            enumAttr("status", "status of the task", Set.of("Task"),
                    List.of("TODO", "IN_PROGRESS", "BLOCKED", "DONE", "CANCELLED"), Set.of()),
            enumAttr("status", "status of the goal", Set.of("Goal"), List.of("OPEN", "ACHIEVED", "ABANDONED"),
                    Set.of()),
            enumAttr("status", "status of the decision", Set.of("Decision"),
                    List.of("PROPOSED", "ACCEPTED", "REVERSED", DECISION_STATUS_SUPERSEDED),
                    Set.of(DECISION_STATUS_SUPERSEDED)),
            enumAttr("category", "kind of technology", Set.of("Technology"),
                    List.of("LANGUAGE", "FRAMEWORK", "LIBRARY", "DATABASE", "TOOL", "PLATFORM", "SERVICE", "MODEL",
                            "OTHER"), Set.of()),
            enumAttr("category", "kind of standard", Set.of("Standard"),
                    List.of("LICENSE", "SPECIFICATION", "PROTOCOL", "FORMAT", "REGULATION", "OTHER"), Set.of()),
            enumAttr("category", "kind of artifact", Set.of("Artifact"),
                    List.of("REPOSITORY", "DOCUMENT", "DATASET", "MODEL", "DESIGN", "MEDIA", "OTHER"), Set.of()),
            enumAttr("category", "kind of organization", Set.of("Organization"),
                    List.of("COMPANY", "TEAM", "COMMUNITY", "INSTITUTION", "GOVERNMENT", "OTHER"), Set.of()),
            enumAttr("category", "kind of place", Set.of("Place"),
                    List.of("COUNTRY", "REGION", "CITY", "SITE", "ROOM", "VIRTUAL", "OTHER"), Set.of()),
            stringAttr("role", "job or function, e.g. \"backend engineer\"", Set.of("Person", "Agent"), 100),
            stringAttr("version", "version identifier", Set.of("Technology", "Standard", "Artifact"), 50),
            new AttributePredicateDef("homepage", "home page URL (stored only, never fetched)",
                    Set.of("Project", "Technology", "Standard", "Organization", "Artifact"), ValueType.URL, List.of(),
                    2000, null, null, ONE, Set.of(), true),
            stringAttr("rationale", "why the decision was made", Set.of("Decision"), 500),
            dateAttr("decidedOn", "date the decision was made", Set.of("Decision")),
            dateAttr("dueDate", "date the work is due", Set.of("Task", "Goal")),
            dateAttr("startDate", "start date", Set.of("Event", "Project")),
            dateAttr("endDate", "end date", Set.of("Event", "Project")),
            enumAttr("priority", "priority", Set.of("Task", "Goal"), List.of("LOW", "MEDIUM", "HIGH", "CRITICAL"),
                    Set.of()),
            enumAttr("strength", "how strongly the preference is held", Set.of("Preference"),
                    List.of("WEAK", "MODERATE", "STRONG"), Set.of()),
            stringAttr("detail", "additional detail", Set.of("Preference", "Skill"), 300));

    static {
        java.util.HashSet<String> reserved = new java.util.HashSet<>(SYSTEM_LABELS);
        reserved.add("System");
        reserved.add("Internal");
        RESERVED_LABELS = Set.copyOf(reserved);
    }

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
        return ENTITY_TYPES.stream().anyMatch(t -> t.name().equals(name));
    }

    public static boolean isCoreRelation(String name) {
        return RELATION_PREDICATES.stream().anyMatch(p -> p.name().equals(name));
    }

    public static boolean isCoreAttributeName(String name) {
        return ATTRIBUTE_PREDICATES.stream().anyMatch(p -> p.name().equals(name));
    }

    /** Distinct attribute predicate names in the core profile. */
    public static Set<String> attributeNames() {
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        ATTRIBUTE_PREDICATES.forEach(a -> names.add(a.name()));
        return java.util.Collections.unmodifiableSortedSet(names);
    }

    private static EntityTypeDef type(String name, String description, String group, String... examples) {
        return new EntityTypeDef(name, description, List.of(examples), group, true);
    }

    private static RelationPredicateDef rel(String name, String description, TypeSet sources, TypeSet targets,
            Cardinality cardinality) {
        return RelationPredicateDef.simple(name, description, sources, targets, cardinality, true);
    }

    private static AttributePredicateDef enumAttr(String name, String description, Set<String> types,
            List<String> values, Set<String> engineOnly) {
        return new AttributePredicateDef(name, description, types, ValueType.ENUM, values, null, null, null, ONE,
                engineOnly, true);
    }

    private static AttributePredicateDef stringAttr(String name, String description, Set<String> types, int max) {
        return new AttributePredicateDef(name, description, types, ValueType.STRING, List.of(), max, null, null, ONE,
                Set.of(), true);
    }

    private static AttributePredicateDef dateAttr(String name, String description, Set<String> types) {
        return new AttributePredicateDef(name, description, types, ValueType.PARTIAL_DATE, List.of(), null,
                (BigDecimal) null, null, ONE, Set.of(), true);
    }
}
