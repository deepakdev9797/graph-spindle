package io.graphspindle.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ConflictPolicy;
import io.graphspindle.common.Origin;
import io.graphspindle.graph.internal.AliasStore;
import io.graphspindle.graph.internal.ClaimStore;
import io.graphspindle.graph.internal.ConflictStore;
import io.graphspindle.graph.internal.EntityStore;
import io.graphspindle.graph.internal.GraphTransactions;
import io.graphspindle.graph.internal.ProjectionStore;
import io.graphspindle.graph.internal.ScopedTx;
import io.graphspindle.graph.internal.SourceRefStore;
import io.graphspindle.vocabulary.BrainVocabulary;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.exceptions.ClientException;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M7: a uniqueness violation while applying a plan (a concurrent ingestion created the same entity
 * or claim key first) rolls the transaction back and the plan is applied once more; a second
 * violation is not retried.
 */
class ConstraintRetryIT extends AbstractGraphIT {

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

    /** An entity store whose first {@code failures} upserts fail like a lost MERGE race, after writing. */
    private GraphStore racing(int failures, AtomicInteger calls) {
        EntityStore flaky = new EntityStore() {
            @Override
            public Merged mergeByKey(ScopedTx tx, BrainVocabulary vocabulary, String type, String canonicalKey,
                    String name, String normalizedName, String description, String origin, String ingestionId) {
                Merged m = super.mergeByKey(tx, vocabulary, type, canonicalKey, name, normalizedName, description,
                        origin, ingestionId);
                if (calls.incrementAndGet() <= failures) {
                    throw new ClientException(GraphTransactions.CONSTRAINT_VIOLATION,
                            "Node already exists with label `Entity` and property `canonicalKey`");
                }
                return m;
            }
        };
        return new GraphStore(transactions, flaky, aliasStore, claimStore, projectionStore, sourceRefStore,
                conflictStore);
    }

    private ApplyResult applyWith(GraphStore store, BrainScope s) {
        return store.apply(s, new ApplyRequest(ingestion(s, "tester").id(), "tester", Origin.LLM, List.of(),
                plan(create(0, "k", "Technology", "Kafka Streams")), CORE,
                ConflictPolicy.EXPLICIT_CORRECTIONS_SUPERSEDE, true, null));
    }

    @Test
    void aConstraintViolationIsRetriedOnceAndTheFirstAttemptRolledBack() {
        BrainScope a = brain("cr-a");
        BrainScope b = brain("cr-b");
        AtomicInteger calls = new AtomicInteger();
        ApplyResult r = applyWith(racing(1, calls), a);
        assertThat(r.attempts()).isEqualTo(2);
        assertThat(r.status()).isEqualTo("APPLIED");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(count("MATCH (n:Entity {brainId:'cr-a', name:'Kafka Streams'}) RETURN count(n)", Map.of()))
                .isEqualTo(1);
        assertThat(count("MATCH (n:Entity {brainId:'cr-b', name:'Kafka Streams'}) RETURN count(n)", Map.of()))
                .isZero();
        assertThat(b.brainId()).isEqualTo("cr-b");
    }

    @Test
    void aSecondViolationIsNotRetried() {
        BrainScope a = brain("cr-c");
        brain("cr-d");
        AtomicInteger calls = new AtomicInteger();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> applyWith(racing(2, calls), a))
                .satisfies(e -> assertThat(GraphTransactions.isConstraintViolation(e)).isTrue());
        assertThat(calls.get()).isEqualTo(2);
        assertThat(count("MATCH (n:Entity {brainId:'cr-c', name:'Kafka Streams'}) RETURN count(n)", Map.of()))
                .isZero();
    }
}
