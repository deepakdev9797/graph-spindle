package io.graphspindle.brain;

import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.vocabulary.AttributePredicateDef;
import io.graphspindle.vocabulary.BrainVocabulary;
import io.graphspindle.vocabulary.EntityTypeDef;
import io.graphspindle.vocabulary.NameSimilarity;
import io.graphspindle.vocabulary.NameSimilarity.Kind;
import io.graphspindle.vocabulary.NameSimilarity.Term;
import io.graphspindle.vocabulary.RelationPredicateDef;
import io.graphspindle.vocabulary.VocabularyConfig;
import io.graphspindle.vocabulary.VocabularyRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The similarity guardrail (ADR-025): a new or renamed node or relationship type must not be a
 * near-duplicate of an existing type, synonym or property. Rejections are {@code CONFLICT_STATE}
 * (409) with {@code similarTo}, {@code matchedTerm}, {@code reason} and a {@code hint}.
 */
final class VocabularyGuardrails {

    private VocabularyGuardrails() {
    }

    /** Known node-type terms: every core (also disabled) and custom type, their synonyms, and all properties. */
    static List<Term> nodeTypeTerms(VocabularyRegistry registry, VocabularyConfig config, String exclude) {
        BrainVocabulary all = registry.resolveUnchecked(config.withEnabledEntityTypes(null));
        List<Term> terms = new ArrayList<>();
        for (EntityTypeDef t : all.entityTypes()) {
            if (t.name().equals(exclude)) {
                continue;
            }
            terms.add(new Term(t.name(), Kind.NODE_TYPE, t.name()));
            t.synonyms().forEach(s -> terms.add(new Term(s, Kind.NODE_TYPE_SYNONYM, t.name())));
        }
        for (EntityTypeDef t : all.entityTypes()) {
            if (t.name().equals(exclude)) {
                continue;
            }
            for (AttributePredicateDef a : all.attributesOf(t.name())) {
                String target = t.name() + "." + a.name();
                terms.add(new Term(a.name(), Kind.PROPERTY, target));
                a.synonyms().forEach(s -> terms.add(new Term(s, Kind.PROPERTY_SYNONYM, target)));
            }
        }
        return terms;
    }

    /** Known relationship terms: core (also disabled) and custom relationship types and their synonyms. */
    static List<Term> relationTerms(VocabularyRegistry registry, VocabularyConfig config, String exclude) {
        List<Term> terms = new ArrayList<>();
        for (RelationPredicateDef r : registry.profile().relationPredicates()) {
            if (!r.name().equals(exclude)) {
                terms.add(new Term(r.name(), Kind.RELATIONSHIP_TYPE, r.name()));
                r.synonyms().forEach(s -> terms.add(new Term(s, Kind.RELATIONSHIP_TYPE_SYNONYM, r.name())));
            }
        }
        for (VocabularyConfig.CustomRelationPredicate r : config.customRelationPredicates()) {
            if (r.name() != null && !r.name().equals(exclude)) {
                terms.add(new Term(r.name(), Kind.RELATIONSHIP_TYPE, r.name()));
                r.synonyms().forEach(s -> terms.add(new Term(s, Kind.RELATIONSHIP_TYPE_SYNONYM, r.name())));
            }
        }
        return terms;
    }

    static void checkNodeTypeName(VocabularyRegistry registry, VocabularyConfig config, String candidate,
            String exclude) {
        check("node type", candidate, nodeTypeTerms(registry, config, exclude));
    }

    static void checkRelationName(VocabularyRegistry registry, VocabularyConfig config, String candidate,
            String exclude) {
        check("relationship type", candidate, relationTerms(registry, config, exclude));
    }

    /**
     * For a bulk {@code PUT /vocabulary}: custom type and relation names that are new compared to
     * {@code before} must not be similar to the core vocabulary or to the types that existed before.
     */
    static void checkNewNamesNotSimilar(VocabularyRegistry registry, VocabularyConfig before, VocabularyConfig after) {
        Set<String> oldTypes = new HashSet<>();
        before.customEntityTypes().forEach(t -> oldTypes.add(t.name()));
        for (VocabularyConfig.CustomEntityType t : after.customEntityTypes()) {
            if (t != null && t.name() != null && !oldTypes.contains(t.name())) {
                checkNodeTypeName(registry, before, t.name(), null);
            }
        }
        Set<String> oldRelations = new HashSet<>();
        before.customRelationPredicates().forEach(r -> oldRelations.add(r.name()));
        for (VocabularyConfig.CustomRelationPredicate r : after.customRelationPredicates()) {
            if (r != null && r.name() != null && !oldRelations.contains(r.name())) {
                checkRelationName(registry, before, r.name(), null);
            }
        }
    }

    private static void check(String what, String candidate, List<Term> terms) {
        Optional<NameSimilarity.Match> m = NameSimilarity.findSimilar(candidate, terms);
        if (m.isEmpty()) {
            return;
        }
        NameSimilarity.Match match = m.get();
        String hint = match.hint(io.graphspindle.common.Normalizer.truncate(candidate, 64));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "SIMILAR_NAME");
        details.put("similarTo", match.target());
        details.put("matchedTerm", match.term());
        details.put("matchKind", match.kind().name());
        details.put("match", match.reason());
        details.put("hint", hint);
        boolean sameName = "SAME_NAME".equals(match.reason())
                && (match.kind() == Kind.NODE_TYPE || match.kind() == Kind.RELATIONSHIP_TYPE);
        throw new GraphSpindleException(sameName ? ErrorCode.ALREADY_EXISTS : ErrorCode.CONFLICT_STATE,
                (sameName ? "a " + what + " with this name already exists: " : what + " name is too similar to "
                        + "existing vocabulary: ") + hint, details, null);
    }
}
