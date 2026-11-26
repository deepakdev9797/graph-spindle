package io.graphspindle.vocabulary;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * A relation predicate (projected as a typed edge, SCHEMA §6.3).
 *
 * @param name upper-snake name, also the projection relationship type
 * @param description the meaning given to the LLM
 * @param sourceTypes allowed subject types
 * @param targetTypes allowed object types (independent of the subject type)
 * @param targetSameAsSource the object may additionally have the same type as the subject ({@code PART_OF})
 * @param extraTargetsBySource additional object types allowed for specific subject types ({@code PART_OF})
 * @param cardinality ONE or MANY per subject
 * @param confidenceCap maximum confidence for claims with this predicate, or null for none
 * @param core true when it comes from the core profile
 * @param synonyms alternative names or phrasings (prompt and similarity guardrail, ADR-025)
 */
public record RelationPredicateDef(
        String name,
        String description,
        TypeSet sourceTypes,
        TypeSet targetTypes,
        boolean targetSameAsSource,
        Map<String, Set<String>> extraTargetsBySource,
        Cardinality cardinality,
        Double confidenceCap,
        boolean core,
        List<String> synonyms) {

    public RelationPredicateDef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(sourceTypes, "sourceTypes");
        Objects.requireNonNull(targetTypes, "targetTypes");
        Objects.requireNonNull(cardinality, "cardinality");
        TreeMap<String, Set<String>> extras = new TreeMap<>();
        if (extraTargetsBySource != null) {
            extraTargetsBySource.forEach((k, v) -> extras.put(k, Set.copyOf(v)));
        }
        extraTargetsBySource = java.util.Collections.unmodifiableSortedMap(extras);
        synonyms = List.copyOf(synonyms == null ? List.of() : synonyms);
    }

    /** A predicate without synonyms. */
    public RelationPredicateDef(String name, String description, TypeSet sourceTypes, TypeSet targetTypes,
            boolean targetSameAsSource, Map<String, Set<String>> extraTargetsBySource, Cardinality cardinality,
            Double confidenceCap, boolean core) {
        this(name, description, sourceTypes, targetTypes, targetSameAsSource, extraTargetsBySource, cardinality,
                confidenceCap, core, List.of());
    }

    /** A simple predicate with fixed source and target sets. */
    public static RelationPredicateDef simple(String name, String description, TypeSet sources, TypeSet targets,
            Cardinality cardinality, boolean core) {
        return new RelationPredicateDef(name, description, sources, targets, false, Map.of(), cardinality, null, core);
    }

    /** Whether the type pair is allowed by this definition (enabled-ness is checked by the caller). */
    public boolean allows(String sourceType, String targetType) {
        if (!sourceTypes.contains(sourceType)) {
            return false;
        }
        if (targetTypes.contains(targetType)) {
            return true;
        }
        if (targetSameAsSource && sourceType.equals(targetType)) {
            return true;
        }
        return extraTargetsBySource.getOrDefault(sourceType, Set.of()).contains(targetType);
    }

    public RelationPredicateDef withCardinality(Cardinality c) {
        return new RelationPredicateDef(name, description, sourceTypes, targetTypes, targetSameAsSource,
                extraTargetsBySource, c, confidenceCap, core, synonyms);
    }

    /** Restricts source, target and extra types to the enabled types. */
    public RelationPredicateDef restrictTo(Set<String> enabled) {
        TreeMap<String, Set<String>> extras = new TreeMap<>();
        extraTargetsBySource.forEach((src, targets) -> {
            if (enabled.contains(src)) {
                Set<String> kept = new java.util.TreeSet<>(targets);
                kept.retainAll(enabled);
                if (!kept.isEmpty()) {
                    extras.put(src, kept);
                }
            }
        });
        return new RelationPredicateDef(name, description, sourceTypes.restrictTo(enabled),
                targetTypes.restrictTo(enabled), targetSameAsSource, extras, cardinality, confidenceCap, core, synonyms);
    }

    /** True when at least one (source, target) pair is still possible. */
    public boolean isUsable() {
        return !sourceTypes.isEmpty() && (!targetTypes.isEmpty() || targetSameAsSource || !extraTargetsBySource.isEmpty());
    }

    /** Human readable target description for prompts. */
    public String describeTargets() {
        StringBuilder sb = new StringBuilder();
        if (!targetTypes.isEmpty()) {
            sb.append(targetTypes.describe());
        }
        if (targetSameAsSource) {
            if (!sb.isEmpty()) {
                sb.append("; or ");
            }
            sb.append("the same type as the subject");
        }
        extraTargetsBySource.forEach((src, targets) -> sb.append("; or ")
                .append(String.join(", ", new java.util.TreeSet<>(targets))).append(" when the subject is ").append(src));
        return sb.toString();
    }
}
