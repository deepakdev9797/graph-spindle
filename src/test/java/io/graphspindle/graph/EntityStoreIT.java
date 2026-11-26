package io.graphspindle.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.common.BrainScope;
import io.graphspindle.graph.ApplyResult.Outcome;
import io.graphspindle.graph.internal.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EntityStoreIT extends AbstractGraphIT {

    @Autowired
    EntityStore entityStore;

    @Test
    void mergeByCanonicalKeyIsIdempotent() {
        BrainScope a = brain("ent-a");
        ApplyResult first = apply(a, create(0, "e1", "Project", "GraphSpindle", "graph-spindle"));
        ApplyResult second = apply(a, create(0, "x", "Project", "  graphspindle. "));
        String id = first.refs().get("e1");
        assertThat(second.refs().get("x")).isEqualTo(id);
        assertThat(first.outcomes(Outcome.ENTITY_CREATED)).hasSize(1);
        assertThat(second.outcomes(Outcome.ENTITY_MATCHED)).hasSize(1);
        assertThat(count("MATCH (n:Entity:Project {brainId: 'ent-a'}) RETURN count(n)", Map.of())).isEqualTo(1);

        EntityView e = reader.entity(a, id).orElseThrow();
        assertThat(e.canonicalKey()).isEqualTo("project:graphspindle");
        assertThat(e.name()).isEqualTo("GraphSpindle");
        assertThat(e.origin()).isEqualTo("LLM");
        assertThat(e.mentionCount()).isEqualTo(2);
        assertThat(reader.aliasesOf(a, id)).containsExactly("graph-spindle", "graphspindle");
        assertThat(reader.entitiesByAlias(a, "graph-spindle")).extracting(EntityView::id).containsExactly(id);
        assertThat(count("MATCH (:Entity {brainId: 'ent-a', id: $id})-[m:MENTIONED_IN]->(:Ingestion) RETURN count(m)",
                Map.of("id", id))).isEqualTo(2);
    }

    @Test
    void sameKeyInTwoBrainsGivesTwoNodes() {
        BrainScope a = brain("ent-a");
        BrainScope b = brain("ent-b");
        String inA = apply(a, create(0, "e", "Technology", "Neo4j")).refs().get("e");
        String inB = apply(b, create(0, "e", "Technology", "Neo4j")).refs().get("e");
        assertThat(inA).isNotEqualTo(inB);
        assertThat(count("MATCH (n:Entity {canonicalKey: 'technology:neo4j'}) RETURN count(n)", Map.of())).isEqualTo(2);
        assertThat(count("MATCH (a:Alias {normalized: 'neo4j'}) RETURN count(a)", Map.of())).isEqualTo(2);
        assertThat(reader.entity(a, inB)).isEmpty();
        assertThat(reader.entitiesByAlias(a, "neo4j")).extracting(EntityView::id).containsExactly(inA);
    }

    @Test
    void concurrentCreateFromEightThreadsLeavesOneNode() throws Exception {
        BrainScope a = brain("ent-a");
        List<ApplyResult> results = parallel(8, () -> apply(a, create(0, "e", "Technology", "Spring AI", "springai")));
        assertThat(results).extracting(r -> r.refs().get("e")).containsOnly(results.get(0).refs().get("e"));
        assertThat(count("MATCH (n:Entity {brainId: 'ent-a', canonicalKey: 'technology:spring ai'}) RETURN count(n)",
                Map.of())).isEqualTo(1);
        assertThat(count("MATCH (a:Alias {brainId: 'ent-a'}) RETURN count(a)", Map.of())).isEqualTo(2);
        assertThat(count("MATCH (:Entity {brainId: 'ent-a'})-[h:HAS_ALIAS]->(:Alias) RETURN count(h)", Map.of()))
                .isEqualTo(2);
        assertThat(reader.entityByKey(a, "technology:spring ai").orElseThrow().mentionCount()).isEqualTo(8);
    }

    @Test
    void followsMergedIntoToTheSurvivor() {
        BrainScope a = brain("ent-a");
        ApplyResult r = apply(a, create(0, "l", "Project", "Graph Spindle"), create(1, "s", "Project", "GraphSpindle"));
        String loser = r.refs().get("l");
        String survivor = r.refs().get("s");
        transactions.write(a, tx -> {
            entityStore.markMerged(tx, loser, survivor, "tester");
            return null;
        });
        assertThat(reader.resolveEntity(a, loser).orElseThrow().id()).isEqualTo(survivor);
        assertThat(reader.entity(a, loser).orElseThrow().status()).isEqualTo("MERGED");
        // a later create with the loser's key and a claim targeting the loser both land on the survivor
        ApplyResult later = apply(a, create(0, "x", "Project", "Graph Spindle"),
                claim(1, relation(existing(selfId(a), "Person"), "WORKS_ON", existing(loser, "Project"),
                        io.graphspindle.common.Polarity.POSITIVE)));
        assertThat(later.refs().get("x")).isEqualTo(survivor);
        assertThat(reader.claimsOfSubject(a, selfId(a))).singleElement()
                .satisfies(c -> assertThat(c.objectId()).isEqualTo(survivor));
        assertThat(count("MATCH (:Entity {brainId:'ent-a', id:$s})<-[:WORKS_ON]-() RETURN count(*)",
                Map.of("s", survivor))).isEqualTo(1);
    }

    @Test
    void fulltextIsBrainScopedAndEscaped() {
        BrainScope a = brain("ent-a");
        BrainScope b = brain("ent-b");
        apply(a, create(0, "e", "Project", "GraphSpindle"));
        apply(b, create(0, "e", "Project", "GraphSpindle"));
        apply(a, create(0, "w", "Organization", "The Who"));
        assertThat(reader.fulltextEntities(a, "graphspindle", 5)).singleElement()
                .satisfies(s -> assertThat(s.entity().canonicalKey()).isEqualTo("project:graphspindle"));
        assertThat(reader.fulltextEntities(a, "the who", 5)).extracting(s -> s.entity().name()).contains("The Who");
        assertThat(reader.fulltextEntities(a, "\"unbalanced (quote* OR AND ~", 5)).isEmpty();
        assertThat(reader.fulltextEntities(a, "   ", 5)).isEmpty();
    }

    static <T> List<T> parallel(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
