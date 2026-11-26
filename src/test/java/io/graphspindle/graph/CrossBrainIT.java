package io.graphspindle.graph;

import static io.graphspindle.common.Polarity.POSITIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ConflictPolicy;
import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Origin;
import io.graphspindle.graph.ApplyResult.Outcome;
import io.graphspindle.graph.internal.ClaimStore;
import io.graphspindle.graph.internal.ConflictStore;
import io.graphspindle.graph.internal.EntityStore;
import io.graphspindle.ingestion.validation.PlanCommand;
import io.graphspindle.ingestion.validation.PlanCommand.LinkSourceCommand;
import io.graphspindle.ingestion.validation.PlanCommand.ProposeMergeCommand;
import io.graphspindle.ingestion.validation.PlanCommand.RetractClaimCommand;
import io.graphspindle.ingestion.validation.PlanCommand.UpdateEntityCommand;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A brain A scope combined with ids from brain B never reads, links or changes brain B. */
class CrossBrainIT extends AbstractGraphIT {

    @Autowired
    EntityStore entityStore;
    @Autowired
    ClaimStore claimStore;
    @Autowired
    ConflictStore conflictStore;

    BrainScope a;
    BrainScope b;
    String projectA;
    String projectB;
    String techB;
    String claimB;
    long nodesB;
    long relsB;

    @BeforeEach
    void twoBrains() {
        a = brain("x-a");
        b = brain("x-b");
        projectA = apply(a, create(0, "p", "Project", "GraphSpindle")).refs().get("p");
        ApplyResult rb = apply(b, create(0, "p", "Project", "GraphSpindle"), create(1, "t", "Technology", "Neo4j"),
                claim(2, relation(ref("p", "Project", "GraphSpindle"), "USES", ref("t", "Technology", "Neo4j"),
                        POSITIVE)));
        projectB = rb.refs().get("p");
        techB = rb.refs().get("t");
        claimB = rb.outcomes(Outcome.CLAIM_CREATED).get(0).claimId();
        nodesB = count("MATCH (n {brainId: 'x-b'}) RETURN count(n)", Map.of());
        relsB = count("MATCH ()-[r {brainId: 'x-b'}]->() RETURN count(r)", Map.of());
    }

    void assertBrainBUntouched() {
        assertThat(count("MATCH (n {brainId: 'x-b'}) RETURN count(n)", Map.of())).isEqualTo(nodesB);
        assertThat(count("MATCH ()-[r {brainId: 'x-b'}]->() RETURN count(r)", Map.of())).isEqualTo(relsB);
        assertThat(count("MATCH (n {brainId: 'x-a'})-[r]-(m) WHERE m.brainId <> 'x-a' OR r.brainId <> 'x-a'"
                + " RETURN count(r)", Map.of())).isZero();
        assertThat(reader.claim(b, claimB).orElseThrow().status()).isEqualTo("ACTIVE");
    }

    void assertNotFound(PlanCommand command) {
        IngestionRecord i = ingestion(a, "tester");
        long nodesA = count("MATCH (n {brainId: 'x-a'}) RETURN count(n)", Map.of());
        assertThatThrownBy(() -> graph.apply(a, new ApplyRequest(i.id(), "tester", Origin.LLM, List.of(
                new SourceInput("FILE", "docs/a.md", null, null, null, null)), plan(command), CORE,
                ConflictPolicy.EXPLICIT_CORRECTIONS_SUPERSEDE, true, null)))
                .isInstanceOf(GraphSpindleException.class)
                .satisfies(e -> assertThat(((GraphSpindleException) e).code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(count("MATCH (n {brainId: 'x-a'}) RETURN count(n)", Map.of())).isEqualTo(nodesA);
        assertThat(ingestions.find(a, i.id()).orElseThrow().status()).isEqualTo("RECEIVED");
        assertBrainBUntouched();
    }

    @Test
    void readsWithForeignIdsFindNothing() {
        assertThat(reader.entity(a, projectB)).isEmpty();
        assertThat(reader.resolveEntity(a, projectB)).isEmpty();
        assertThat(reader.claim(a, claimB)).isEmpty();
        assertThat(reader.claimsOfSubject(a, projectB)).isEmpty();
        assertThat(reader.entityByKey(a, "technology:neo4j")).isEmpty();
        assertThat(reader.entitiesByAlias(a, "neo4j")).isEmpty();
        assertThat(reader.fulltextEntities(a, "neo4j", 10)).isEmpty();
        assertThat(ingestions.find(a, "nope")).isEmpty();
    }

    @Test
    void claimsAgainstForeignEntitiesAreNotFound() {
        assertNotFound(claim(0, relation(existing(projectA, "Project"), "USES", existing(techB, "Technology"),
                POSITIVE)));
        assertNotFound(claim(0, relation(existing(projectB, "Project"), "USES", existing(projectA, "Project"),
                POSITIVE)));
        assertNotFound(claim(0, attribute(existing(techB, "Technology"), "category", "DATABASE")));
    }

    @Test
    void entityOperationsWithForeignIdsAreNotFound() {
        assertNotFound(new UpdateEntityCommand(0, existing(projectB, "Project"), "hijacked", null));
        assertNotFound(new PlanCommand.AddAliasCommand(0, existing(projectB, "Project"),
                new io.graphspindle.ingestion.validation.AliasSpec("evil", "evil")));
        assertNotFound(new LinkSourceCommand(0, 0, existing(techB, "Technology")));
        assertNotFound(new ProposeMergeCommand(0, existing(projectA, "Project"), existing(projectB, "Project"), "x"));
    }

    @Test
    void claimCorrectionsWithForeignClaimIdsAreNotFound() {
        assertNotFound(new RetractClaimCommand(0, claimB, "bye", "evidence"));
        assertNotFound(new PlanCommand.SupersedeClaimCommand(0, claimB, true, "x",
                relation(existing(projectA, "Project"), "USES", existing(projectA, "Project"), POSITIVE)));
        assertNotFound(new PlanCommand.FlagConflictCommand(0, claimB,
                attribute(existing(projectA, "Project"), "status", "ACTIVE"), "x"));
    }

    @Test
    void lowLevelStoreCallsAcrossBrainsFindNothingAndCreateNothing() {
        transactions.write(a, tx -> {
            assertThat(entityStore.findById(tx, projectB)).isEmpty();
            assertThat(claimStore.findById(tx, claimB)).isEmpty();
            assertThatThrownBy(() -> entityStore.markMerged(tx, projectA, projectB, "x"))
                    .isInstanceOf(GraphSpindleException.class);
            String conflict = conflictStore.create(tx, "DUPLICATE_CANDIDATE", "x", null);
            assertThatThrownBy(() -> conflictStore.involveEntity(tx, conflict, projectB, "CANDIDATE_A"))
                    .isInstanceOf(GraphSpindleException.class);
            assertThatThrownBy(() -> conflictStore.involveClaim(tx, conflict, claimB, "EXISTING"))
                    .isInstanceOf(GraphSpindleException.class);
            return null;
        });
        assertBrainBUntouched();
    }

    @Test
    void scopedTransactionsRefuseUnscopedOrForeignStatements() {
        assertThatThrownBy(() -> transactions.read(a, tx -> tx.run("MATCH (n) RETURN n").list()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transactions.read(a, tx -> tx.run("MATCH (n {brainId: $brainId}) RETURN n",
                Map.of("brainId", "x-b")).list())).isInstanceOf(IllegalArgumentException.class);
    }
}
