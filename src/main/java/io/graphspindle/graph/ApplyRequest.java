package io.graphspindle.graph;

import io.graphspindle.common.ConflictPolicy;
import io.graphspindle.common.Origin;
import io.graphspindle.ingestion.validation.ValidatedPlan;
import io.graphspindle.vocabulary.BrainVocabulary;
import java.util.List;
import java.util.Objects;

/**
 * Everything {@link GraphStore#apply} needs to write a validated plan in one transaction.
 *
 * @param ingestionId an Ingestion of this brain in status RECEIVED
 * @param principal who submitted it ({@code createdByPrincipal}; LATEST_WINS compares it)
 * @param origin origin of the knowledge written ({@code LLM} for ingestion)
 * @param sources the request's sources; plan source indexes refer to this list
 * @param vocabulary the brain vocabulary: the whitelist for labels, relationship types and attributes
 * @param policy the brain conflict policy (SCHEMA §9.4)
 * @param allowLlmSupersession whether explicit LLM corrections may supersede LLM claims
 */
public record ApplyRequest(String ingestionId, String principal, Origin origin, List<SourceInput> sources,
        ValidatedPlan plan, BrainVocabulary vocabulary, ConflictPolicy policy, boolean allowLlmSupersession,
        IngestionCompletion completion) {

    public ApplyRequest {
        Objects.requireNonNull(ingestionId, "ingestionId");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(origin, "origin");
        sources = sources == null ? List.of() : List.copyOf(sources);
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(vocabulary, "vocabulary");
        policy = policy == null ? ConflictPolicy.EXPLICIT_CORRECTIONS_SUPERSEDE : policy;
        completion = completion == null ? IngestionCompletion.none(plan.summary()) : completion;
    }
}
