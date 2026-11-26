package io.graphspindle.graph;

import static io.graphspindle.common.Polarity.NEGATIVE;
import static io.graphspindle.common.Polarity.POSITIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.graphspindle.brain.BrainConfig;
import io.graphspindle.brain.BrainUpdate;
import io.graphspindle.brain.NewBrain;
import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Origin;
import io.graphspindle.graph.ApplyResult.Outcome;
import io.graphspindle.ingestion.validation.PlanCommand.RetractClaimCommand;
import io.graphspindle.ingestion.validation.PlanCommand.SupersedeClaimCommand;
import io.graphspindle.ingestion.validation.ResolvedEntity;
import io.graphspindle.vocabulary.BrainVocabulary;
import io.graphspindle.vocabulary.Cardinality;
import io.graphspindle.vocabulary.VocabularyConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Regression tests for the M4 QA follow-ups (ADR-022) beyond the adversarial QA tests. */
class GraphFixesIT extends AbstractGraphIT {

    BrainScope a;
    BrainScope b;
    ResolvedEntity.Existing project;
    ResolvedEntity.Existing neo4j;
    ResolvedEntity.Existing kafka;
    ResolvedEntity.Existing apache;
    ResolvedEntity.Existing mit;

    @BeforeEach
    void entities() {
        a = brain("fx-a");
        b = brain("fx-b");
        ApplyResult r = apply(a, create(0, "p", "Project", "GraphSpindle"), create(1, "t", "Technology", "Neo4j"),
                create(2, "k", "Technology", "Kafka"), create(3, "s1", "Standard", "Apache License 2.0"),
                create(4, "s2", "Standard", "MIT License"));
        project = existing(r.refs().get("p"), "Project");
        neo4j = existing(r.refs().get("t"), "Technology");
        kafka = existing(r.refs().get("k"), "Technology");
        apache = existing(r.refs().get("s1"), "Standard");
        mit = existing(r.refs().get("s2"), "Standard");
        apply(b, create(0, "p", "Project", "GraphSpindle"));
    }

    /** Supersede whose replacement is contradicted by another ACTIVE claim: nothing is superseded. */
    @Test
    void supersedeBlockedByAContradictingClaimKeepsTheSlot() {
        String old = apply(a, claim(0, relation(project, "LICENSED_UNDER", apache, POSITIVE)))
                .outcomes(Outcome.CLAIM_CREATED).get(0).claimId();
        String notMit = apply(a, claim(0, relation(project, "LICENSED_UNDER", mit, NEGATIVE)))
                .outcomes(Outcome.CLAIM_CREATED).get(0).claimId();

        ApplyResult r = apply(a, new SupersedeClaimCommand(0, old, true, "switch to MIT",
                relation(project, "LICENSED_UNDER", mit, POSITIVE)));

        assertThat(r.outcomes(Outcome.CLAIM_SUPERSEDED)).isEmpty();
        var disputed = r.outcomes(Outcome.CLAIM_DISPUTED).get(0);
        assertThat(reader.claim(a, old).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(reader.claim(a, disputed.claimId()).orElseThrow().status()).isEqualTo("DISPUTED");
        var roles = query("MATCH (x:Conflict {brainId: 'fx-a', id: $id})-[i:INVOLVES]->(c:Claim)"
                + " RETURN i.role + '=' + c.id AS r ORDER BY r", Map.of("id", disputed.conflictId()));
        assertThat(roles).extracting(m -> m.get("r"))
                .containsExactly("EXISTING=" + notMit, "INCOMING=" + disputed.claimId());
        assertThat(count("MATCH ()-[e:LICENSED_UNDER {brainId: 'fx-a'}]->() RETURN count(e)", Map.of())).isEqualTo(1);
        assertThat(verifier.verify(a)).isEmpty();
        assertThat(verifier.verify(b)).isEmpty();
    }

    /** The same for a cardinality-MANY predicate (opposite polarity of the replacement triple). */
    @Test
    void supersedeBlockedOnAManyPredicate() {
        String old = apply(a, claim(0, relation(project, "USES", neo4j, POSITIVE))).outcomes(Outcome.CLAIM_CREATED)
                .get(0).claimId();
        apply(a, claim(0, relation(project, "USES", kafka, NEGATIVE)));
        ApplyResult r = apply(a, new SupersedeClaimCommand(0, old, true, "moved to Kafka",
                relation(project, "USES", kafka, POSITIVE)));
        assertThat(r.outcomes(Outcome.CLAIM_DISPUTED)).hasSize(1);
        assertThat(reader.claim(a, old).orElseThrow().active()).isTrue();
        // an unblocked supersede still works
        ApplyResult ok = apply(a, new SupersedeClaimCommand(0, old, true, "moved to MIT-licensed lib",
                relation(project, "USES", apache, POSITIVE)));
        assertThat(ok.outcomes(Outcome.CLAIM_SUPERSEDED)).hasSize(1);
        assertThat(verifier.verify(a)).isEmpty();
    }

    /** Repeated identical contradictions: one DISPUTED claim and one conflict, evidence accumulates. */
    @Test
    void repeatedContradictionReinforcesTheOpenDispute() {
        apply(a, claim(0, relation(project, "LICENSED_UNDER", apache, POSITIVE)));
        ApplyResult first = apply(a, claim(0, relation(project, "LICENSED_UNDER", mit, POSITIVE)));
        ApplyResult second = apply(a, claim(0, relation(project, "LICENSED_UNDER", mit, POSITIVE)));
        ApplyResult third = apply(a, claim(0, relation(project, "LICENSED_UNDER", mit, POSITIVE)));
        String disputed = first.outcomes(Outcome.CLAIM_DISPUTED).get(0).claimId();
        assertThat(second.outcomes(Outcome.DISPUTE_REINFORCED)).singleElement()
                .satisfies(o -> assertThat(o.claimId()).isEqualTo(disputed));
        assertThat(third.status()).isEqualTo("APPLIED");
        assertThat(count("MATCH (c:Claim {brainId: 'fx-a', status: 'DISPUTED'}) RETURN count(c)", Map.of()))
                .isEqualTo(1);
        assertThat(count("MATCH (x:Conflict {brainId: 'fx-a'}) RETURN count(x)", Map.of())).isEqualTo(1);
        assertThat(reader.claim(a, disputed).orElseThrow().evidenceCount()).isEqualTo(3);
        // once the conflict is no longer OPEN, a new contradiction opens a new one
        write("MATCH (x:Conflict {brainId: 'fx-a'}) SET x.status = 'RESOLVED'", Map.of());
        assertThat(apply(a, claim(0, relation(project, "LICENSED_UNDER", mit, POSITIVE)))
                .outcomes(Outcome.CLAIM_DISPUTED)).hasSize(1);
        assertThat(count("MATCH (c:Claim {brainId: 'fx-b'}) RETURN count(c)", Map.of())).isZero();
    }

    static VocabularyConfig recipes() {
        return new VocabularyConfig(null, null, List.of(), Map.of(),
                List.of(new VocabularyConfig.CustomEntityType("Recipe", "A cooking recipe", null, List.of())),
                List.of(new VocabularyConfig.CustomRelationPredicate("INGREDIENT_OF", "is an ingredient of",
                        List.of("Concept"), List.of("Recipe"), Cardinality.MANY)),
                List.of());
    }

    /** SCHEMA §11: config PATCH and vocabulary PUT cannot remove a custom type or predicate in use. */
    @Test
    void customVocabularyInUseCannotBeRemoved() {
        brains.create(new NewBrain("fx-v", "v", null, null, BrainConfig.defaults().withVocabulary(recipes())));
        BrainScope s = BrainScope.of("fx-v");
        BrainVocabulary v = brains.vocabulary(s);
        IngestionRecord i = ingestion(s, "tester");
        ApplyResult r = graph.apply(s, new ApplyRequest(i.id(), "tester", Origin.LLM, List.of(),
                plan(create(0, "r", "Recipe", "Pancakes"), create(1, "c", "Concept", "Flour"),
                        claim(2, relationIn(v, ref("c", "Concept", "Flour"), "INGREDIENT_OF",
                                ref("r", "Recipe", "Pancakes")))), v, null, true, null));
        String claimId = r.outcomes(Outcome.CLAIM_CREATED).get(0).claimId();

        // an explicit core vocabulary removes the custom one (an absent vocabulary keeps it, ADR-029)
        assertThatThrownBy(() -> brains.update("fx-v", null, new BrainUpdate(null, null,
                BrainConfig.defaults().withVocabulary(VocabularyConfig.core()))))
                .isInstanceOf(GraphSpindleException.class)
                .satisfies(e -> {
                    assertThat(((GraphSpindleException) e).code()).isEqualTo(ErrorCode.CONFLICT_STATE);
                    assertThat(((GraphSpindleException) e).details().get("inUse").toString())
                            .contains("Recipe", "INGREDIENT_OF");
                });
        // keeping the type but removing the predicate is refused too
        VocabularyConfig typeOnly = new VocabularyConfig(null, null, List.of(), Map.of(), recipes().customEntityTypes(),
                List.of(), List.of());
        assertThatThrownBy(() -> brains.updateVocabulary("fx-v", null, typeOnly))
                .extracting(e -> ((GraphSpindleException) e).code()).isEqualTo(ErrorCode.CONFLICT_STATE);
        assertThat(brains.get("fx-v").configVersion()).isEqualTo(1);

        // once nothing uses the predicate it can be removed; the type is still used by the entity
        graph.apply(s, new ApplyRequest(ingestion(s, "tester").id(), "tester", Origin.LLM, List.of(),
                plan(new RetractClaimCommand(0, claimId, "wrong", "evidence")), v, null, true, null));
        assertThat(brains.updateVocabulary("fx-v", null, typeOnly).brain().configVersion()).isEqualTo(2);
        assertThatThrownBy(() -> brains.updateVocabulary("fx-v", null, VocabularyConfig.core()))
                .extracting(e -> ((GraphSpindleException) e).code()).isEqualTo(ErrorCode.CONFLICT_STATE);
    }

    static io.graphspindle.ingestion.validation.ResolvedClaim relationIn(BrainVocabulary v, ResolvedEntity s,
            String predicate, ResolvedEntity o) {
        var def = v.relation(predicate).orElseThrow();
        return new io.graphspindle.ingestion.validation.ResolvedClaim(s, predicate,
                io.graphspindle.common.ClaimKind.RELATION, def.cardinality(), o, null, null, null, POSITIVE,
                "statement", "evidence", 0.9, null, null, List.of(), null, null);
    }
}
