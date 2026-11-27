package io.graphspindle.graph;

import io.graphspindle.common.Actor;
import io.graphspindle.brain.BrainService;
import io.graphspindle.brain.NewBrain;
import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ClaimKind;
import io.graphspindle.common.ConflictPolicy;
import io.graphspindle.common.Ids;
import io.graphspindle.common.Normalizer;
import io.graphspindle.common.Origin;
import io.graphspindle.common.PartialDate;
import io.graphspindle.common.Polarity;
import io.graphspindle.graph.internal.GraphTransactions;
import io.graphspindle.ingestion.validation.AliasSpec;
import io.graphspindle.ingestion.validation.PlanCommand;
import io.graphspindle.ingestion.validation.PlanCommand.CreateEntityCommand;
import io.graphspindle.ingestion.validation.ResolvedClaim;
import io.graphspindle.ingestion.validation.ResolvedEntity;
import io.graphspindle.ingestion.validation.ValidatedPlan;
import io.graphspindle.support.AbstractNeo4jIT;
import io.graphspindle.vocabulary.AttributePredicateDef;
import io.graphspindle.vocabulary.BrainVocabulary;
import io.graphspindle.vocabulary.VocabularyRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

/** Helpers to build plans by hand and apply them (graph ITs need no LLM and no validator). */
abstract class AbstractGraphIT extends AbstractNeo4jIT {

    static final BrainVocabulary CORE = new VocabularyRegistry().core();

    @Autowired
    protected BrainService brains;
    @Autowired
    protected GraphStore graph;
    @Autowired
    protected GraphReader reader;
    @Autowired
    protected IngestionStore ingestions;
    @Autowired
    protected ProjectionVerifier verifier;
    @Autowired
    protected GraphTransactions transactions;

    protected BrainScope brain(String id) {
        brains.create(Actor.LOCAL_ADMIN, new NewBrain(id, "Brain " + id, null, null, null));
        return BrainScope.of(id);
    }

    protected String selfId(BrainScope scope) {
        return brains.get(Actor.LOCAL_ADMIN, scope.brainId()).selfEntityId();
    }

    protected IngestionRecord ingestion(BrainScope scope, String principal) {
        return ingestions.createReceived(scope, new NewIngestion("req-" + Ids.newId(), "hash", "NOTE", 10, null, null,
                principal, null, null));
    }

    protected ApplyResult apply(BrainScope scope, PlanCommand... commands) {
        return apply(scope, "tester", ConflictPolicy.EXPLICIT_CORRECTIONS_SUPERSEDE, List.of(), commands);
    }

    protected ApplyResult apply(BrainScope scope, String principal, ConflictPolicy policy, List<SourceInput> sources,
            PlanCommand... commands) {
        IngestionRecord i = ingestion(scope, principal);
        return graph.apply(scope, new ApplyRequest(i.id(), principal, Origin.LLM, sources, plan(commands), CORE,
                policy, true, null));
    }

    static ValidatedPlan plan(PlanCommand... commands) {
        return new ValidatedPlan("test", Arrays.asList(commands), List.of(), List.of(), Map.of());
    }

    static CreateEntityCommand create(int op, String ref, String type, String name, String... aliases) {
        String norm = Normalizer.norm(name);
        return new CreateEntityCommand(op, new ResolvedEntity.New(ref, type, Normalizer.canonicalKey(type, norm)),
                name, norm, null, Arrays.stream(aliases).map(a -> new AliasSpec(a, Normalizer.norm(a))).toList());
    }

    static ResolvedEntity.New ref(String ref, String type, String name) {
        return new ResolvedEntity.New(ref, type, Normalizer.canonicalKey(type, Normalizer.norm(name)));
    }

    static ResolvedEntity.Existing existing(String id, String type) {
        return new ResolvedEntity.Existing(id, type, "k");
    }

    static ResolvedClaim relation(ResolvedEntity s, String predicate, ResolvedEntity o, Polarity polarity) {
        return relation(s, predicate, o, polarity, 0.9, null);
    }

    static ResolvedClaim relation(ResolvedEntity s, String predicate, ResolvedEntity o, Polarity polarity,
            double confidence, PartialDate validFrom) {
        var def = CORE.relation(predicate).orElseThrow();
        return new ResolvedClaim(s, predicate, ClaimKind.RELATION, def.cardinality(), o, null, null, null, polarity,
                s.type() + " " + predicate + " " + o.type(), "evidence", confidence, validFrom, null, List.of(), null,
                null);
    }

    static ResolvedClaim attribute(ResolvedEntity s, String predicate, String value) {
        AttributePredicateDef def = CORE.attribute(predicate, s.type()).orElseThrow();
        return new ResolvedClaim(s, predicate, ClaimKind.ATTRIBUTE, def.cardinality(), null, def.valueType(), value,
                value, Polarity.POSITIVE, predicate + " is " + value, "evidence", 0.9, null, null, List.of(), null,
                null);
    }

    static PlanCommand.AssertClaimCommand claim(int op, ResolvedClaim c) {
        return new PlanCommand.AssertClaimCommand(op, c);
    }
}
