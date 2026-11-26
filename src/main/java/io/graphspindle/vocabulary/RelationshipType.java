package io.graphspindle.vocabulary;

import java.util.List;
import java.util.Map;

/**
 * A relationship type (relation predicate) as shown and accepted by the relationship-type API
 * (ADR-025). On PUT, fields left null keep their current value.
 *
 * @param name upper-snake name
 * @param description meaning given to the LLM (required on create)
 * @param synonyms alternative phrasings, rendered into the prompt
 * @param sourceTypes allowed subject types ({@code ["Any"]} for any type)
 * @param targetTypes allowed object types ({@code ["Any"]} for any type)
 * @param targetSameAsSource read-only (core only): the object may have the subject's type
 * @param extraTargetsBySource read-only (core only): extra object types per subject type
 * @param cardinality ONE or MANY (default MANY)
 * @param confidenceCap read-only (core only): maximum confidence
 * @param core read-only: true for a type from the YAML definition
 * @param enabled whether it is enabled in the brain (only core types can be disabled)
 */
public record RelationshipType(String name, String description, List<String> synonyms, List<String> sourceTypes,
        List<String> targetTypes, Boolean targetSameAsSource, Map<String, List<String>> extraTargetsBySource,
        Cardinality cardinality, Double confidenceCap,
        Boolean core, Boolean enabled) {

    public static RelationshipType of(RelationPredicateDef d, boolean enabled) {
        java.util.TreeMap<String, List<String>> extras = new java.util.TreeMap<>();
        d.extraTargetsBySource().forEach((k, v) -> extras.put(k, List.copyOf(new java.util.TreeSet<>(v))));
        return new RelationshipType(d.name(), d.description(), d.synonyms(), names(d.sourceTypes()),
                names(d.targetTypes()), d.targetSameAsSource(), extras, d.cardinality(), d.confidenceCap(), d.core(),
                enabled);
    }

    private static List<String> names(TypeSet t) {
        return t.anyType() ? List.of(VocabularyDefinitionLoader.ANY) : List.copyOf(t.types());
    }
}
