package io.graphspindle.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.graphspindle.brain.BrainConfig;
import io.graphspindle.brain.BrainService;
import io.graphspindle.brain.BrainUpdate;
import io.graphspindle.brain.NewBrain;
import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ClaimKind;
import io.graphspindle.common.ConflictPolicy;
import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Origin;
import io.graphspindle.common.Polarity;
import io.graphspindle.graph.ApplyResult.Outcome;
import io.graphspindle.graph.internal.AliasStore;
import io.graphspindle.graph.internal.ClaimStore;
import io.graphspindle.graph.internal.ConflictStore;
import io.graphspindle.graph.internal.EntityStore;
import io.graphspindle.graph.internal.ProjectionStore;
import io.graphspindle.graph.internal.ScopedTx;
import io.graphspindle.graph.internal.SourceRefStore;
import io.graphspindle.ingestion.validation.PlanCommand;
import io.graphspindle.ingestion.validation.PlanCommand.RetractClaimCommand;
import io.graphspindle.ingestion.validation.ResolvedClaim;
import io.graphspindle.ingestion.validation.ResolvedEntity;
import io.graphspindle.vocabulary.AttributePredicateDef;
import io.graphspindle.vocabulary.BrainVocabulary;
import io.graphspindle.vocabulary.ValueType;
import io.graphspindle.vocabulary.VocabularyConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * QA review of M1/M2/M4: each test confirmed a finding (it failed on a426e70) and now guards its fix.
 * Two set-ups were adapted to the fixes while keeping the assertions' intent: F1's retract test can no
 * longer remove an in-use attribute through the service (that is the F1 fix), so it writes the config
 * directly, as a brain configured before the guard existed would be; F2's apply now holds the Brain
 * lock, so the delete waits for it instead of marking DELETING underneath it (ADR-022).
 */
class QaAdversarialIT extends AbstractGraphIT {

    @Autowired
    BrainStore brainStore;
    @Autowired
    JsonMapper jsonMapper;
    @Autowired
    EntityStore entityStore;
    @Autowired
    AliasStore aliasStore;
    @Autowired
    ClaimStore claimStore;
    @Autowired
    ProjectionStore projectionStore;
    @Autowired
    ConflictStore conflictStore;
    @Autowired
    SourceRefStore sourceRefStore;

    // ------------------------------------------------------------------ helpers

    ApplyResult applyWith(BrainScope scope, BrainVocabulary vocab, PlanCommand... commands) {
        IngestionRecord i = ingestion(scope, "tester");
        return graph.apply(scope, new ApplyRequest(i.id(), "tester", Origin.LLM, List.of(), plan(commands), vocab,
                ConflictPolicy.EXPLICIT_CORRECTIONS_SUPERSEDE, true, null));
    }

    static VocabularyConfig withFlavor() {
        return new VocabularyConfig(null, null, List.of(), Map.of(), List.of(), List.of(),
                List.of(new VocabularyConfig.CustomAttributePredicate("flavor", List.of("Technology"),
                        ValueType.STRING, 100, List.of(), null, null)));
    }

    static ResolvedClaim attr(BrainVocabulary v, ResolvedEntity s, String predicate, String value) {
        AttributePredicateDef def = v.attribute(predicate, s.type()).orElseThrow();
        return new ResolvedClaim(s, predicate, ClaimKind.ATTRIBUTE, def.cardinality(), null, def.valueType(), value,
                value, Polarity.POSITIVE, predicate + " is " + value, "evidence", 0.9, null, null, List.of(), null,
                null);
    }

    // ------------------------------------------------------------------ F1 vocabulary removal of an in-use predicate

    /** SCHEMA §11: removing a custom predicate is rejected (409) while ACTIVE claims use it. */
    @Test
    void f1_removingAnInUseCustomAttributeIsRejected() {
        brains.create(new NewBrain("qa-v1", "v", null, null, BrainConfig.defaults().withVocabulary(withFlavor())));
        BrainScope s = BrainScope.of("qa-v1");
        BrainVocabulary v = brains.vocabulary(s);
        String t = applyWith(s, v, create(0, "t", "Technology", "Neo4j")).refs().get("t");
        applyWith(s, v, claim(0, attr(v, existing(t, "Technology"), "flavor", "graphy")));

        assertThatThrownBy(() -> brains.updateVocabulary("qa-v1", null, VocabularyConfig.core()))
                .isInstanceOf(GraphSpindleException.class)
                .extracting(e -> ((GraphSpindleException) e).code()).isEqualTo(ErrorCode.CONFLICT_STATE);
    }

    /** Consequence of F1: the ACTIVE claim can no longer be retracted; every such ingestion fails as a whole. */
    @Test
    void f1_claimOfARemovedAttributeCanStillBeRetracted() {
        brains.create(new NewBrain("qa-v2", "v", null, null, BrainConfig.defaults().withVocabulary(withFlavor())));
        BrainScope s = BrainScope.of("qa-v2");
        BrainVocabulary v = brains.vocabulary(s);
        String t = applyWith(s, v, create(0, "t", "Technology", "Neo4j")).refs().get("t");
        String c = applyWith(s, v, claim(0, attr(v, existing(t, "Technology"), "flavor", "graphy")))
                .outcomes(Outcome.CLAIM_CREATED).get(0).claimId();
        // the service now refuses this removal (F1 fix); write the config directly, as a brain configured
        // before the in-use guard existed (or one whose predicate was removed some other way) would have it
        brainStore.update(s, null, null, false, null, jsonMapper.writeValueAsString(BrainConfig.defaults()));
        BrainVocabulary after = brains.vocabulary(s);
        assertThat(after.hasAttributeName("flavor")).isFalse();

        assertThatCode(() -> applyWith(s, after, new RetractClaimCommand(0, c, "wrong", "evidence")))
                .doesNotThrowAnyException();
        assertThat(reader.entity(s, t).orElseThrow().attributes()).doesNotContainKey("flavor");
    }

    // ------------------------------------------------------------------ F2 brain delete races a running apply

    /**
     * GraphStore.checkBrainAndIngestion reads the Brain node without a lock. A delete that starts while
     * an apply is running is not blocked: the apply commits APPLIED into a DELETING brain (ARCHITECTURE §12
     * says DELETING blocks all other operations), and nodes it creates can survive DELETE_ALL, so a brain
     * re-created with the same id inherits them.
     */
    @Test
    void f2_applyCannotCommitIntoABrainThatIsBeingDeleted() throws Exception {
        BrainScope s = brain("qa-del");
        CountDownLatch inApply = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        SourceRefStore slowSources = new SourceRefStore() {
            @Override
            public String upsert(ScopedTx tx, SourceInput in, String ingestionId) {
                String id = super.upsert(tx, in, ingestionId);
                if (first.getAndSet(false)) {
                    inApply.countDown();
                    try {
                        release.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return id;
            }
        };
        GraphStore racing = new GraphStore(transactions, entityStore, aliasStore, claimStore, projectionStore,
                slowSources, conflictStore);
        IngestionRecord i = ingestion(s, "tester");
        List<PlanCommand> creates = new ArrayList<>();
        for (int k = 0; k < 20; k++) {
            creates.add(create(k, "e" + k, "Technology", "Orphan " + k));
        }
        CompletableFuture<ApplyResult> applying = CompletableFuture.supplyAsync(() -> racing.apply(s,
                new ApplyRequest(i.id(), "tester", Origin.LLM, List.of(new SourceInput("MESSAGE", "m1", null, null,
                        null, null)), plan(creates.toArray(PlanCommand[]::new)), CORE,
                        ConflictPolicy.EXPLICIT_CORRECTIONS_SUPERSEDE, true, null)));
        assertThat(inApply.await(20, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> deleting = CompletableFuture.runAsync(() -> brainStore.delete(s));
        // before the fix the delete committed DELETING underneath the running apply; now it must wait for
        // the Brain lock held by the apply, so DELETING never becomes visible while the apply runs
        long deadline = System.currentTimeMillis() + 2_000;
        boolean sawDeleting = false;
        while (System.currentTimeMillis() < deadline && !sawDeleting) {
            sawDeleting = count("MATCH (b:Brain {id: 'qa-del', status: 'DELETING'}) RETURN count(b)", Map.of()) > 0;
            Thread.sleep(20);
        }
        release.countDown();
        Throwable applyError = null;
        try {
            applying.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            applyError = e.getCause();
        }
        deleting.get(30, TimeUnit.SECONDS);
        long leftovers = count("MATCH (n {brainId: 'qa-del'}) RETURN count(n)", Map.of());

        // expected: the apply never commits into a DELETING brain (it is refused, or it committed before the
        // delete could mark the brain) and nothing of the brain is left
        var soft = new org.assertj.core.api.SoftAssertions();
        soft.assertThat(applyError == null && sawDeleting).as("apply committed into a DELETING brain").isFalse();
        if (applyError != null) {
            soft.assertThat(applyError).isInstanceOf(GraphSpindleException.class);
        }
        soft.assertThat(leftovers).as("nodes of the deleted brain that survived DELETE_ALL").isZero();
        soft.assertAll();
        // a new apply into a brain that is being deleted (or is gone) is refused
        assertThatThrownBy(() -> ingestion(s, "tester")).isInstanceOf(GraphSpindleException.class);
    }

    /** A write that starts after DELETING is refused with CONFLICT_STATE (the Brain lock re-reads the status). */
    @Test
    void f2_applyIntoADeletingBrainIsRefused() {
        BrainScope s = brain("qa-del2");
        IngestionRecord i = ingestion(s, "tester");
        write("MATCH (b:Brain {id: 'qa-del2'}) SET b.status = 'DELETING'", Map.of());
        assertThatThrownBy(() -> graph.apply(s, new ApplyRequest(i.id(), "tester", Origin.LLM, List.of(),
                plan(create(0, "e", "Technology", "Late")), CORE, ConflictPolicy.EXPLICIT_CORRECTIONS_SUPERSEDE,
                true, null))).isInstanceOf(GraphSpindleException.class)
                .extracting(e -> ((GraphSpindleException) e).code()).isEqualTo(ErrorCode.CONFLICT_STATE);
        assertThatThrownBy(() -> ingestion(s, "tester")).isInstanceOf(GraphSpindleException.class);
        assertThat(count("MATCH (n:Entity {brainId: 'qa-del2', name: 'Late'}) RETURN count(n)", Map.of())).isZero();
    }

    // ------------------------------------------------------------------ F3 fulltext starvation by other brains

    /** Oversampling 5x before the brainId filter: another brain's better matches starve this brain's results. */
    @Test
    void f3_fulltextResultsDoNotDependOnOtherBrains() {
        BrainScope a = brain("qa-fa");
        BrainScope b = brain("qa-fb");
        apply(a, create(0, "x", "Technology", "Kafka streaming platform for event pipelines"));
        List<PlanCommand> noise = new ArrayList<>();
        for (int k = 0; k < 60; k++) {
            noise.add(create(k, "n" + k, "Technology", "Kafka " + k));
        }
        apply(b, noise.toArray(PlanCommand[]::new));

        assertThat(reader.fulltextEntities(a, "kafka", 10)).hasSize(1);
    }

    /** Brain ids that share hyphen-separated parts still scope fulltext exactly ("qa-x" vs "z-qa-x"). */
    @Test
    void f3_fulltextScopeTokenIsExactForHyphenatedIds() {
        BrainScope a = brain("qa-x");
        BrainScope b = brain("z-qa-x");
        apply(a, create(0, "x", "Technology", "Kafka"));
        List<PlanCommand> noise = new ArrayList<>();
        for (int k = 0; k < 60; k++) {
            noise.add(create(k, "n" + k, "Technology", "Kafka " + k));
        }
        apply(b, noise.toArray(PlanCommand[]::new));
        assertThat(reader.fulltextEntities(a, "kafka", 3)).hasSize(1);
        assertThat(reader.fulltextEntities(b, "kafka", 50)).hasSize(50);
    }

    // ------------------------------------------------------------------ F4 vocabulary PUT loses concurrent PATCH

    /** updateVocabulary reads the config, then writes it back without a version check (If-Match omitted). */
    @Test
    void f4_vocabularyPutDoesNotRevertAConcurrentConfigPatch() {
        brains.create(new NewBrain("qa-lu", "lu", null, null, null));
        AtomicBoolean once = new AtomicBoolean(true);
        BrainService real = brains;
        BrainStore racingStore = new BrainStore(transactions) {
            @Override
            public BrainRecord update(BrainScope scope, Long expectedVersion, String name, boolean setDescription,
                    String description, String configJson) {
                if (once.getAndSet(false)) {
                    // another admin's PATCH lands between updateVocabulary's read and its write
                    real.update("qa-lu", null, new BrainUpdate(null, null, new BrainConfig(ConflictPolicy.ALWAYS_FLAG,
                            null, null, null, null, null, null, null, null)));
                }
                return super.update(scope, expectedVersion, name, setDescription, description, configJson);
            }
        };
        BrainService svc = new BrainService(racingStore, jsonMapper);
        svc.updateVocabulary("qa-lu", null, withFlavor());

        assertThat(brains.get("qa-lu").config().conflictPolicy()).isEqualTo(ConflictPolicy.ALWAYS_FLAG);
    }
}
