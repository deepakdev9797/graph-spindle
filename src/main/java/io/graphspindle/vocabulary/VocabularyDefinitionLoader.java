package io.graphspindle.vocabulary;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads the core vocabulary profile from YAML and validates it (ADR-025). The bundled definition is
 * {@code classpath:vocabulary/core-v1.yml}; an optional operator file is merged over it by name:
 * entity and relationship types with the same name replace the bundled entry, new ones are
 * appended, {@code removeEntityTypes} / {@code removeRelationshipTypes} drop entries, and
 * {@code classificationHints}, when present, replace the bundled hints.
 *
 * <p>Every problem is collected and reported together in an {@link InvalidVocabularyDefinitionException}.
 */
public final class VocabularyDefinitionLoader {

    public static final String BUNDLED = "vocabulary/core-v1.yml";
    public static final String ANY = "Any";

    static final int MAX_SYNONYMS = 10;
    static final int MAX_SYNONYM_LENGTH = 60;
    static final int MAX_EXAMPLES = 5;
    static final int MAX_EXAMPLE_LENGTH = 100;
    static final int MAX_PROPERTIES_PER_TYPE = 20;
    static final int MAX_HINTS = 20;
    static final int MAX_HINT_LENGTH = 300;
    private static final Pattern GROUP = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private VocabularyDefinitionLoader() {
    }

    // ---- YAML shape --------------------------------------------------------------------------

    record Definition(String profile, List<HintSpec> classificationHints, List<TypeSpec> entityTypes,
            List<RelationSpec> relationshipTypes, List<String> removeEntityTypes,
            List<String> removeRelationshipTypes) {
    }

    record HintSpec(String text, String requiresType) {
    }

    record TypeSpec(String name, String description, String group, List<String> synonyms, List<String> examples,
            List<PropertySpec> properties) {
    }

    record PropertySpec(String name, String description, ValueType type, Integer maxLength, List<String> values,
            List<String> engineOnlyValues, BigDecimal min, BigDecimal max, Cardinality cardinality, Boolean required,
            List<String> synonyms) {
    }

    record RelationSpec(String name, String description, List<String> synonyms, List<String> sources,
            List<String> targets, Boolean targetSameAsSource, Map<String, List<String>> extraTargetsBySource,
            Cardinality cardinality, Double confidenceCap) {
    }

    // ---- entry points ------------------------------------------------------------------------

    /** The bundled profile. */
    public static CoreProfile bundled() {
        return load(null, null);
    }

    /**
     * Loads the bundled profile with an optional override merged over it.
     *
     * @param override the operator file, or null
     * @param overrideName a description of the override for messages
     */
    public static CoreProfile load(InputStream override, String overrideName) {
        Definition base = read(VocabularyDefinitionLoader.class.getClassLoader().getResourceAsStream(BUNDLED),
                "classpath:" + BUNDLED);
        String source = "classpath:" + BUNDLED;
        if (override != null) {
            Definition extra = read(override, overrideName);
            base = merge(base, extra);
            source = source + " + " + overrideName;
        }
        return build(base, source);
    }

    /** Loads exactly one definition without the bundled base (tests and tooling). */
    public static CoreProfile loadStandalone(InputStream in, String name) {
        return build(read(in, name), name);
    }

    private static Definition read(InputStream in, String name) {
        if (in == null) {
            throw new InvalidVocabularyDefinitionException(name, List.of("resource not found"), null);
        }
        try (in) {
            Definition d = YAML.readValue(in, Definition.class);
            if (d == null) {
                throw new InvalidVocabularyDefinitionException(name, List.of("the file is empty"), null);
            }
            return d;
        } catch (JacksonException e) {
            throw new InvalidVocabularyDefinitionException(name, List.of("unreadable YAML: " + e.getOriginalMessage()
                    + location(e)), e);
        } catch (IOException e) {
            throw new InvalidVocabularyDefinitionException(name, List.of("cannot read: " + e.getMessage()), e);
        }
    }

    private static String location(JacksonException e) {
        var loc = e.getLocation();
        String path = e.getPathReference();
        return (loc == null ? "" : " (line " + loc.getLineNr() + ", column " + loc.getColumnNr() + ")")
                + (path == null ? "" : " at " + path);
    }

    static Definition merge(Definition base, Definition extra) {
        LinkedHashMap<String, TypeSpec> types = new LinkedHashMap<>();
        list(base.entityTypes()).forEach(t -> types.put(String.valueOf(t.name()), t));
        list(extra.removeEntityTypes()).forEach(types::remove);
        list(extra.entityTypes()).forEach(t -> types.put(String.valueOf(t.name()), t));
        LinkedHashMap<String, RelationSpec> rels = new LinkedHashMap<>();
        list(base.relationshipTypes()).forEach(r -> rels.put(String.valueOf(r.name()), r));
        list(extra.removeRelationshipTypes()).forEach(rels::remove);
        list(extra.relationshipTypes()).forEach(r -> rels.put(String.valueOf(r.name()), r));
        List<HintSpec> hints = extra.classificationHints() != null ? extra.classificationHints()
                : base.classificationHints();
        String profile = extra.profile() != null ? extra.profile() : base.profile();
        return new Definition(profile, hints, List.copyOf(types.values()), List.copyOf(rels.values()), null, null);
    }

    // ---- validation and building -------------------------------------------------------------

    private static CoreProfile build(Definition d, String source) {
        List<String> problems = new ArrayList<>();
        if (!CoreVocabulary.PROFILE.equals(d.profile())) {
            problems.add("profile: must be " + CoreVocabulary.PROFILE);
        }
        if (d.removeEntityTypes() != null || d.removeRelationshipTypes() != null) {
            if (!list(d.removeEntityTypes()).isEmpty() || !list(d.removeRelationshipTypes()).isEmpty()) {
                problems.add("removeEntityTypes/removeRelationshipTypes are only allowed in an override file");
            }
        }
        List<EntityTypeDef> types = new ArrayList<>();
        Set<String> typeNames = new HashSet<>();
        Set<String> lowerTypes = new HashSet<>();
        // attribute definitions grouped by everything except the entity type, in first-seen order
        LinkedHashMap<PropertyKey, Set<String>> attributes = new LinkedHashMap<>();
        List<TypeSpec> typeSpecs = list(d.entityTypes());
        if (typeSpecs.isEmpty()) {
            problems.add("entityTypes: at least one entity type is required");
        }
        for (int i = 0; i < typeSpecs.size(); i++) {
            TypeSpec t = typeSpecs.get(i);
            String path = "entityTypes[" + i + (t != null && t.name() != null ? ":" + t.name() : "") + "]";
            if (t == null) {
                problems.add(path + ": entry is null");
                continue;
            }
            if (!VocabularyConfigValidator.matches(VocabularyConfigValidator.TYPE_NAME, t.name())) {
                problems.add(path + ".name: must match " + VocabularyConfigValidator.TYPE_NAME.pattern());
            } else if (CoreVocabulary.isReservedLabel(t.name())) {
                problems.add(path + ".name: '" + t.name() + "' is a reserved label");
            } else if (!lowerTypes.add(CoreProfile.lower(t.name()))) {
                problems.add(path + ".name: duplicate type (names are compared ignoring case)");
            } else {
                typeNames.add(t.name());
            }
            description(path, t.description(), problems);
            if (t.group() != null && !GROUP.matcher(t.group()).matches()) {
                problems.add(path + ".group: must match " + GROUP.pattern());
            }
            texts(path + ".synonyms", t.synonyms(), MAX_SYNONYMS, MAX_SYNONYM_LENGTH, problems);
            texts(path + ".examples", t.examples(), MAX_EXAMPLES, MAX_EXAMPLE_LENGTH, problems);
            List<PropertySpec> props = list(t.properties());
            if (props.size() > MAX_PROPERTIES_PER_TYPE) {
                problems.add(path + ".properties: at most " + MAX_PROPERTIES_PER_TYPE + " properties per type");
            }
            Set<String> propNames = new HashSet<>();
            for (int j = 0; j < props.size(); j++) {
                PropertySpec p = props.get(j);
                String ppath = path + ".properties[" + j + (p != null && p.name() != null ? ":" + p.name() : "") + "]";
                if (p == null) {
                    problems.add(ppath + ": entry is null");
                    continue;
                }
                property(ppath, p, problems);
                if (p.name() != null && !propNames.add(CoreProfile.lower(p.name()))) {
                    problems.add(ppath + ".name: duplicate property (names are compared ignoring case)");
                }
                if (t.name() != null && p.name() != null && p.type() != null) {
                    attributes.computeIfAbsent(PropertyKey.of(p), k -> new java.util.TreeSet<>()).add(t.name());
                }
            }
            if (t.name() != null && t.description() != null) {
                types.add(new EntityTypeDef(t.name(), t.description().strip(), list(t.synonyms()),
                        list(t.examples()), t.group(), true));
            }
        }
        if (!typeNames.contains(CoreVocabulary.PERSON)) {
            problems.add("entityTypes: the type Person is required (the brain's self entity is a Person)");
        }
        if (types.size() > VocabularyConfigValidator.MAX_ENABLED_TYPES) {
            problems.add("entityTypes: at most " + VocabularyConfigValidator.MAX_ENABLED_TYPES + " entity types");
        }

        List<RelationPredicateDef> relations = new ArrayList<>();
        Set<String> lowerRelations = new HashSet<>();
        List<RelationSpec> relSpecs = list(d.relationshipTypes());
        for (int i = 0; i < relSpecs.size(); i++) {
            RelationSpec r = relSpecs.get(i);
            String path = "relationshipTypes[" + i + (r != null && r.name() != null ? ":" + r.name() : "") + "]";
            if (r == null) {
                problems.add(path + ": entry is null");
                continue;
            }
            if (!VocabularyConfigValidator.matches(VocabularyConfigValidator.RELATION_NAME, r.name())) {
                problems.add(path + ".name: must match " + VocabularyConfigValidator.RELATION_NAME.pattern());
            } else if (CoreVocabulary.SYSTEM_RELATIONSHIP_TYPES.stream().anyMatch(s -> s.equalsIgnoreCase(r.name()))
                    || CoreVocabulary.isReservedLabel(r.name())) {
                problems.add(path + ".name: '" + r.name() + "' is a system relationship type or reserved name");
            } else if (!lowerRelations.add(CoreProfile.lower(r.name()))) {
                problems.add(path + ".name: duplicate relationship type (names are compared ignoring case)");
            }
            description(path, r.description(), problems);
            texts(path + ".synonyms", r.synonyms(), MAX_SYNONYMS, MAX_SYNONYM_LENGTH, problems);
            TypeSet sources = typeSet(path + ".sources", r.sources(), typeNames, false, problems);
            TypeSet targets = typeSet(path + ".targets", r.targets(), typeNames, true, problems);
            Map<String, Set<String>> extras = new LinkedHashMap<>();
            if (r.extraTargetsBySource() != null) {
                r.extraTargetsBySource().forEach((src, tgts) -> {
                    if (!typeNames.contains(src)) {
                        problems.add(path + ".extraTargetsBySource: unknown entity type '" + src + "'");
                    }
                    for (String tgt : list(tgts)) {
                        if (!typeNames.contains(tgt)) {
                            problems.add(path + ".extraTargetsBySource." + src + ": unknown entity type '" + tgt + "'");
                        }
                    }
                    extras.put(src, Set.copyOf(list(tgts)));
                });
            }
            boolean sameAsSource = Boolean.TRUE.equals(r.targetSameAsSource());
            if (targets != null && targets.isEmpty() && !sameAsSource && extras.isEmpty()) {
                problems.add(path + ".targets: at least one target type is required");
            }
            if (r.confidenceCap() != null && !(r.confidenceCap() > 0 && r.confidenceCap() <= 1)) {
                problems.add(path + ".confidenceCap: must be within (0, 1]");
            }
            if (r.name() != null && r.description() != null && sources != null && targets != null) {
                relations.add(new RelationPredicateDef(r.name(), r.description().strip(), sources, targets,
                        sameAsSource, extras, r.cardinality() == null ? Cardinality.MANY : r.cardinality(),
                        r.confidenceCap(), true, list(r.synonyms())));
            }
        }

        List<AttributePredicateDef> attributeDefs = new ArrayList<>();
        attributes.forEach((k, ts) -> attributeDefs.add(k.toDef(ts)));
        Set<String> attributeNames = new HashSet<>();
        attributeDefs.forEach(a -> attributeNames.add(CoreProfile.lower(a.name())));
        for (String a : new java.util.TreeSet<>(attributeNames)) {
            if (lowerRelations.contains(a)) {
                problems.add("property '" + a + "' and a relationship type differ only in case");
            }
        }
        int predicates = relations.size() + attributeNames.size();
        if (predicates > VocabularyConfigValidator.MAX_ENABLED_PREDICATES) {
            problems.add("at most " + VocabularyConfigValidator.MAX_ENABLED_PREDICATES
                    + " predicates (relationship types plus distinct property names), got " + predicates);
        }

        List<ClassificationHint> hints = new ArrayList<>();
        List<HintSpec> hintSpecs = list(d.classificationHints());
        if (hintSpecs.size() > MAX_HINTS) {
            problems.add("classificationHints: at most " + MAX_HINTS);
        }
        for (int i = 0; i < hintSpecs.size(); i++) {
            HintSpec h = hintSpecs.get(i);
            String path = "classificationHints[" + i + "]";
            if (h == null || h.text() == null || h.text().isBlank()) {
                problems.add(path + ".text: is required");
                continue;
            }
            if (h.text().length() > MAX_HINT_LENGTH) {
                problems.add(path + ".text: at most " + MAX_HINT_LENGTH + " characters");
            }
            if (h.requiresType() != null && !typeNames.contains(h.requiresType())) {
                problems.add(path + ".requiresType: unknown entity type '" + h.requiresType() + "'");
            }
            hints.add(new ClassificationHint(h.text().strip(), h.requiresType()));
        }
        if (!problems.isEmpty()) {
            throw new InvalidVocabularyDefinitionException(source, problems, null);
        }
        return new CoreProfile(d.profile(), source, types, relations, attributeDefs, hints);
    }

    private static void property(String path, PropertySpec p, List<String> problems) {
        List<VocabularyIssue> issues = new ArrayList<>();
        VocabularyConfigValidator.checkAttribute(path, p.name(), p.type(), p.maxLength(), list(p.values()), p.min(),
                p.max(), issues);
        issues.forEach(i -> problems.add(i.path() + ": " + i.message()));
        description(path, p.description(), problems);
        texts(path + ".synonyms", p.synonyms(), MAX_SYNONYMS, MAX_SYNONYM_LENGTH, problems);
        for (String v : list(p.engineOnlyValues())) {
            if (!list(p.values()).contains(v)) {
                problems.add(path + ".engineOnlyValues: '" + v + "' is not one of the values");
            }
        }
    }

    private static TypeSet typeSet(String path, List<String> names, Set<String> known, boolean emptyAllowed,
            List<String> problems) {
        if (names == null) {
            problems.add(path + ": is required (use [Any] for any type)");
            return null;
        }
        if (names.contains(ANY)) {
            if (names.size() > 1) {
                problems.add(path + ": Any cannot be combined with other types");
            }
            return TypeSet.any();
        }
        if (names.isEmpty() && !emptyAllowed) {
            problems.add(path + ": at least one entity type is required");
        }
        for (String n : names) {
            if (!known.contains(n)) {
                problems.add(path + ": unknown entity type '" + n + "'");
            }
        }
        return TypeSet.of(names.stream().filter(Objects::nonNull).toList());
    }

    private static void description(String path, String description, List<String> problems) {
        if (description == null || description.isBlank()) {
            problems.add(path + ".description: is required");
        } else if (description.codePointCount(0, description.length()) > VocabularyConfigValidator.MAX_DESCRIPTION) {
            problems.add(path + ".description: at most " + VocabularyConfigValidator.MAX_DESCRIPTION + " characters");
        }
    }

    static void texts(String path, List<String> values, int maxCount, int maxLength, List<String> problems) {
        List<String> v = list(values);
        if (v.size() > maxCount) {
            problems.add(path + ": at most " + maxCount + " entries");
        }
        Set<String> seen = new HashSet<>();
        for (String s : v) {
            if (s == null || s.isBlank()) {
                problems.add(path + ": entries must not be blank");
            } else if (s.codePointCount(0, s.length()) > maxLength) {
                problems.add(path + ": entries must be at most " + maxLength + " characters");
            } else if (s.chars().anyMatch(Character::isISOControl)) {
                problems.add(path + ": entries must not contain control characters");
            } else if (!seen.add(CoreProfile.lower(s.strip()))) {
                problems.add(path + ": duplicate entry '" + s + "'");
            }
        }
    }

    private static <T> List<T> list(List<T> l) {
        return l == null ? List.of() : l;
    }

    /** A property definition without its entity type, for grouping identical definitions. */
    private record PropertyKey(String name, String description, ValueType type, Integer maxLength, List<String> values,
            List<String> engineOnlyValues, BigDecimal min, BigDecimal max, Cardinality cardinality, boolean required,
            List<String> synonyms) {

        static PropertyKey of(PropertySpec p) {
            Integer max = p.maxLength();
            if (max == null && p.type() == ValueType.STRING) {
                max = VocabularyResolver.DEFAULT_STRING_MAX;
            }
            if (max == null && p.type() == ValueType.URL) {
                max = VocabularyResolver.DEFAULT_URL_MAX;
            }
            return new PropertyKey(p.name(), p.description() == null ? null : p.description().strip(), p.type(), max,
                    list(p.values()), list(p.engineOnlyValues()), p.min(), p.max(),
                    p.cardinality() == null ? Cardinality.ONE : p.cardinality(), Boolean.TRUE.equals(p.required()),
                    list(p.synonyms()));
        }

        AttributePredicateDef toDef(Set<String> types) {
            return new AttributePredicateDef(name, description == null ? name : description, types, type, values,
                    maxLength, min, max, cardinality, Set.copyOf(engineOnlyValues), true, required, synonyms);
        }
    }
}
