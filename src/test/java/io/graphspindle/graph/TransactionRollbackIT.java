package io.graphspindle.graph;

import static io.graphspindle.common.Polarity.POSITIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ClaimKind;
import io.graphspindle.common.ConflictPolicy;
import io.graphspindle.common.Origin;
import io.graphspindle.ingestion.validation.PlanCommand;
import io.graphspindle.ingestion.validation.ResolvedClaim;
import io.graphspindle.vocabulary.Cardinality;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A failure halfway through a multi-op apply leaves no partial nodes or edges. */
class TransactionRollbackIT extends AbstractGraphIT {

    BrainScope a;
    long nodes;
    long rels;

    @BeforeEach
    void setUp() {
        a = brain("rb-a");
        brain("rb-b");
        apply(BrainScope.of("rb-b"), create(0, "p", "Project", "GraphSpindle"));
        nodes = count("MATCH (n) RETURN count(n)", Map.of());
        rels = count("MATCH ()-[r]->() RETURN count(r)", Map.of());
    }

    List<PlanCommand> goodPrefix() {
        var p = ref("p", "Project", "GraphSpindle");
        var t = ref("t", "Technology", "Neo4j");
        return List.of(create(0, "p", "Project", "GraphSpindle", "graph spindle"), create(1, "t", "Technology", "Neo4j"),
                claim(2, relation(p, "USES", t, POSITIVE)), claim(3, attribute(t, "category", "DATABASE")),
                new PlanCommand.ProposeMergeCommand(4, p, existing(selfId(a), "Person"), "test"));
    }

    void assertNothingWritten(IngestionRecord i) {
        // only the RECEIVED ingestion itself was added (in its own transaction)
        assertThat(count("MATCH (n) RETURN count(n)", Map.of())).isEqualTo(nodes + 1);
        assertThat(count("MATCH ()-[r]->() RETURN count(r)", Map.of())).isEqualTo(rels);
        assertThat(count("MATCH (n:Entity {brainId: 'rb-a'}) RETURN count(n)", Map.of())).isEqualTo(1);
        assertThat(count("MATCH (n:Claim|Alias|SourceRef|Conflict {brainId: 'rb-a'}) RETURN count(n)", Map.of()))
                .isZero();
        assertThat(ingestions.find(a, i.id()).orElseThrow().status()).isEqualTo("RECEIVED");
        assertThat(ingestions.markFailed(a, i.id(), "[\"boom\"]")).isTrue();
        assertThat(ingestions.find(a, i.id()).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(ingestions.markFailed(a, i.id(), "again")).isFalse();
    }

    @Test
    void failingSourceIndexHalfwayRollsBackEverything() {
        IngestionRecord i = ingestion(a, "tester");
        List<PlanCommand> commands = new java.util.ArrayList<>(goodPrefix());
        commands.add(new PlanCommand.LinkSourceCommand(5, 3, ref("p", "Project", "GraphSpindle")));
        assertThatThrownBy(() -> graph.apply(a, new ApplyRequest(i.id(), "tester", Origin.LLM,
                List.of(new SourceInput("URL", "https://example.org/a", "A", "about a", null, null)),
                plan(commands.toArray(PlanCommand[]::new)), CORE, ConflictPolicy.EXPLICIT_CORRECTIONS_SUPERSEDE, true,
                null))).isInstanceOf(IllegalArgumentException.class);
        assertNothingWritten(i);
    }

    @Test
    void predicateOutsideTheVocabularyHalfwayRollsBackEverything() {
        IngestionRecord i = ingestion(a, "tester");
        List<PlanCommand> commands = new java.util.ArrayList<>(goodPrefix());
        var p = ref("p", "Project", "GraphSpindle");
        commands.add(claim(5, new ResolvedClaim(p, "OWNS_ALL_DATA", ClaimKind.RELATION, Cardinality.MANY,
                existing(selfId(a), "Person"), null, null, null, POSITIVE, "x", "e", 0.9, null, null, List.of(), null,
                null)));
        assertThatThrownBy(() -> graph.apply(a, new ApplyRequest(i.id(), "tester", Origin.LLM, List.of(),
                plan(commands.toArray(PlanCommand[]::new)), CORE, ConflictPolicy.EXPLICIT_CORRECTIONS_SUPERSEDE, true,
                null))).isInstanceOf(IllegalArgumentException.class);
        assertNothingWritten(i);
        assertThat(count("CALL db.relationshipTypes() YIELD relationshipType WHERE relationshipType = 'OWNS_ALL_DATA'"
                + " RETURN count(*)", Map.of())).isZero();
    }

    @Test
    void appliedIngestionCannotBeAppliedTwice() {
        IngestionRecord i = ingestion(a, "tester");
        ApplyRequest request = new ApplyRequest(i.id(), "tester", Origin.LLM, List.of(),
                plan(goodPrefix().toArray(PlanCommand[]::new)), CORE, ConflictPolicy.EXPLICIT_CORRECTIONS_SUPERSEDE,
                true, null);
        assertThat(graph.apply(a, request).status()).isEqualTo("APPLIED");
        assertThatThrownBy(() -> graph.apply(a, request)).isInstanceOf(
                io.graphspindle.common.GraphSpindleException.class);
    }
}
