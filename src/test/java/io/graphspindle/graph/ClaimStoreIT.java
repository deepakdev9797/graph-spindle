package io.graphspindle.graph;

import static io.graphspindle.common.Polarity.NEGATIVE;
import static io.graphspindle.common.Polarity.POSITIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ConflictPolicy;
import io.graphspindle.graph.ApplyResult.Outcome;
import io.graphspindle.graph.internal.GraphTransactions;
import io.graphspindle.ingestion.validation.PlanCommand.RetractClaimCommand;
import io.graphspindle.ingestion.validation.PlanCommand.SupersedeClaimCommand;
import io.graphspindle.ingestion.validation.ResolvedClaim;
import io.graphspindle.ingestion.validation.ResolvedEntity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.exceptions.Neo4jException;

class ClaimStoreIT extends AbstractGraphIT {

    BrainScope a;
    ResolvedEntity.Existing project;
    ResolvedEntity.Existing neo4j;
    ResolvedEntity.Existing apache;
    ResolvedEntity.Existing mit;

    @BeforeEach
    void entities() {
        a = brain("cl-a");
        brain("cl-b");
        ApplyResult r = apply(a, create(0, "p", "Project", "GraphSpindle"), create(1, "t", "Technology", "Neo4j"),
                create(2, "s1", "Standard", "Apache License 2.0"), create(3, "s2", "Standard", "MIT License"));
        project = existing(r.refs().get("p"), "Project");
        neo4j = existing(r.refs().get("t"), "Technology");
        apache = existing(r.refs().get("s1"), "Standard");
        mit = existing(r.refs().get("s2"), "Standard");
    }

    @Test
    void sameActiveKeyReinforces() {
        ApplyResult first = apply(a, claim(0, relation(project, "USES", neo4j, POSITIVE, 0.7, null)));
        ApplyResult second = apply(a, claim(0, relation(project, "USES", neo4j, POSITIVE, 0.9, null)));
        String claimId = first.outcomes(Outcome.CLAIM_CREATED).get(0).claimId();
        assertThat(second.outcomes(Outcome.CLAIM_REINFORCED)).singleElement()
                .satisfies(o -> assertThat(o.claimId()).isEqualTo(claimId));
        ClaimView c = reader.claim(a, claimId).orElseThrow();
        assertThat(c.evidenceCount()).isEqualTo(2);
        assertThat(c.confidence()).isEqualTo(0.9);
        assertThat(c.activeKey()).isNotNull();
        assertThat(c.slotKey()).isNull();
        assertThat(count("MATCH (c:Claim {brainId: 'cl-a'}) RETURN count(c)", Map.of())).isEqualTo(1);
        assertThat(count("MATCH (:Claim {brainId: 'cl-a'})-[e:EVIDENCED_BY]->(:Ingestion) RETURN count(e)", Map.of()))
                .isEqualTo(2);
        assertThat(query("MATCH ()-[r:USES {brainId: 'cl-a'}]->() RETURN r.confidence AS c", Map.of()))
                .singleElement().satisfies(m -> assertThat(m.get("c")).isEqualTo(0.9));
    }

    @Test
    void slotKeyConstraintBlocksASecondActiveClaimAtTheDatabaseLevel() {
        ApplyResult r = apply(a, claim(0, relation(project, "LICENSED_UNDER", apache, POSITIVE)));
        ClaimView c = reader.claim(a, r.outcomes(Outcome.CLAIM_CREATED).get(0).claimId()).orElseThrow();
        assertThat(c.slotKey()).isNotNull();
        assertThatThrownBy(() -> write("CREATE (:Claim {brainId: 'cl-a', id: 'rogue', status: 'ACTIVE',"
                + " activeKey: 'other', slotKey: $slot})", Map.of("slot", c.slotKey())))
                .isInstanceOf(Neo4jException.class)
                .satisfies(e -> assertThat(GraphTransactions.isConstraintViolation(e)).isTrue());
        // the same slot key in another brain is a different slot
        write("CREATE (:Claim {brainId: 'cl-b', id: 'fine', status: 'ACTIVE', activeKey: 'x', slotKey: $slot})",
                Map.of("slot", c.slotKey()));
    }

    @Test
    void changingStatusRemovesTheKeys() {
        ApplyResult r = apply(a, claim(0, relation(project, "LICENSED_UNDER", apache, POSITIVE)));
        String id = r.outcomes(Outcome.CLAIM_CREATED).get(0).claimId();
        apply(a, new RetractClaimCommand(0, id, "wrong", "evidence"));
        ClaimView c = reader.claim(a, id).orElseThrow();
        assertThat(c.status()).isEqualTo("RETRACTED");
        assertThat(c.activeKey()).isNull();
        assertThat(c.slotKey()).isNull();
        assertThat(c.statusReason()).isEqualTo("wrong");
        // the slot is free again
        ApplyResult again = apply(a, claim(0, relation(project, "LICENSED_UNDER", mit, POSITIVE)));
        assertThat(again.outcomes(Outcome.CLAIM_CREATED)).hasSize(1);
    }

    @Test
    void contradictionOnAOneSlotIsDisputedWithAConflict() {
        String old = apply(a, claim(0, relation(project, "LICENSED_UNDER", apache, POSITIVE)))
                .outcomes(Outcome.CLAIM_CREATED).get(0).claimId();
        ApplyResult r = apply(a, claim(0, relation(project, "LICENSED_UNDER", mit, POSITIVE)));
        var disputed = r.outcomes(Outcome.CLAIM_DISPUTED).get(0);
        ClaimView incoming = reader.claim(a, disputed.claimId()).orElseThrow();
        assertThat(incoming.status()).isEqualTo("DISPUTED");
        assertThat(incoming.activeKey()).isNull();
        assertThat(reader.claim(a, old).orElseThrow().status()).isEqualTo("ACTIVE");
        var roles = query("MATCH (x:Conflict {brainId: 'cl-a', id: $id})-[i:INVOLVES]->(c:Claim)"
                + " RETURN i.role AS role, c.id AS claim ORDER BY role", Map.of("id", disputed.conflictId()));
        assertThat(roles).extracting(m -> m.get("role") + "=" + m.get("claim"))
                .containsExactly("EXISTING=" + old, "INCOMING=" + incoming.id());
        assertThat(count("MATCH (x:Conflict {brainId: 'cl-a', kind: 'CONTRADICTION', status: 'OPEN'})"
                + "-[:RAISED_BY]->(:Ingestion) RETURN count(x)", Map.of())).isEqualTo(1);
        // opposite polarity on the same triple is a contradiction too
        apply(a, claim(0, relation(project, "USES", neo4j, POSITIVE)));
        assertThat(apply(a, claim(0, relation(project, "USES", neo4j, NEGATIVE))).outcomes(Outcome.CLAIM_DISPUTED))
                .hasSize(1);
    }

    @Test
    void explicitSupersedeReplacesTheSlotAndKeepsHistory() {
        String old = apply(a, claim(0, relation(project, "LICENSED_UNDER", apache, POSITIVE)))
                .outcomes(Outcome.CLAIM_CREATED).get(0).claimId();
        ApplyResult r = apply(a, new SupersedeClaimCommand(0, old, true, "license changed",
                relation(project, "LICENSED_UNDER", mit, POSITIVE)));
        String now = r.outcomes(Outcome.CLAIM_CREATED).get(0).claimId();
        assertThat(reader.claim(a, old).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(reader.claim(a, now).orElseThrow().active()).isTrue();
        assertThat(count("MATCH (:Claim {id: $n})-[s:SUPERSEDES {brainId: 'cl-a'}]->(:Claim {id: $o}) RETURN count(s)",
                Map.of("n", now, "o", old))).isEqualTo(1);
        assertThat(reader.claimsOfSubject(a, project.entityId())).extracting(ClaimView::status)
                .containsExactly("SUPERSEDED", "ACTIVE");

        // not explicit: flagged, not applied; ALWAYS_FLAG: even explicit corrections are flagged
        ApplyResult implicit = apply(a, new SupersedeClaimCommand(0, now, false, "maybe",
                relation(project, "LICENSED_UNDER", apache, POSITIVE)));
        assertThat(implicit.outcomes(Outcome.CLAIM_DISPUTED)).hasSize(1);
        ApplyResult flagged = apply(a, "tester", ConflictPolicy.ALWAYS_FLAG, List.of(),
                new SupersedeClaimCommand(0, now, true, "really", relation(project, "LICENSED_UNDER", apache, POSITIVE)));
        // the same contradiction against the same claim is not stored twice: it reinforces the DISPUTED
        // claim of the still OPEN conflict (ADR-022) and is still not applied
        assertThat(flagged.outcomes(Outcome.DISPUTE_REINFORCED)).singleElement().satisfies(o -> {
            assertThat(o.claimId()).isEqualTo(implicit.outcomes(Outcome.CLAIM_DISPUTED).get(0).claimId());
            assertThat(o.conflictId()).isEqualTo(implicit.outcomes(Outcome.CLAIM_DISPUTED).get(0).conflictId());
        });
        assertThat(reader.claim(a, flagged.outcomes(Outcome.DISPUTE_REINFORCED).get(0).claimId()).orElseThrow()
                .evidenceCount()).isEqualTo(2);
        ResolvedEntity.Existing gpl = existing(apply(a, create(0, "g", "Standard", "GPL-3.0")).refs().get("g"),
                "Standard");
        ApplyResult flaggedOther = apply(a, "tester", ConflictPolicy.ALWAYS_FLAG, List.of(),
                new SupersedeClaimCommand(0, now, true, "really", relation(project, "LICENSED_UNDER", gpl, POSITIVE)));
        assertThat(flaggedOther.outcomes(Outcome.CLAIM_DISPUTED)).hasSize(1);
        assertThat(reader.claim(a, now).orElseThrow().active()).isTrue();
    }

    @Test
    void latestWinsSupersedesOnlyForTheSamePrincipal() {
        String old = apply(a, "bot", ConflictPolicy.LATEST_WINS, List.of(),
                claim(0, relation(project, "LICENSED_UNDER", apache, POSITIVE))).outcomes(Outcome.CLAIM_CREATED).get(0)
                .claimId();
        ApplyResult other = apply(a, "someone-else", ConflictPolicy.LATEST_WINS, List.of(),
                claim(0, relation(project, "LICENSED_UNDER", mit, POSITIVE)));
        assertThat(other.outcomes(Outcome.CLAIM_DISPUTED)).hasSize(1);
        ApplyResult same = apply(a, "bot", ConflictPolicy.LATEST_WINS, List.of(),
                claim(0, relation(project, "LICENSED_UNDER", mit, POSITIVE)));
        assertThat(same.outcomes(Outcome.CLAIM_SUPERSEDED)).singleElement()
                .satisfies(o -> assertThat(o.claimId()).isEqualTo(old));
    }

    @Test
    void concurrentIdenticalAssertionsYieldOneReinforcedClaim() throws Exception {
        List<ApplyResult> results = EntityStoreIT.parallel(8,
                () -> apply(a, claim(0, relation(project, "USES", neo4j, POSITIVE))));
        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo("APPLIED"));
        assertThat(count("MATCH (c:Claim {brainId: 'cl-a', predicate: 'USES'}) RETURN count(c)", Map.of()))
                .isEqualTo(1);
        assertThat(count("MATCH (c:Claim {brainId: 'cl-a', predicate: 'USES'}) RETURN c.evidenceCount", Map.of()))
                .isEqualTo(8);
        assertThat(count("MATCH ()-[r:USES {brainId: 'cl-a'}]->() RETURN count(r)", Map.of())).isEqualTo(1);
    }

    @Test
    void concurrentConflictingAssertionsLeaveExactlyOneActiveSlotClaim() throws Exception {
        List<ResolvedEntity.Existing> licenses = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            licenses.add(existing(apply(a, create(0, "s", "Standard", "License " + i)).refs().get("s"), "Standard"));
        }
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        List<ApplyResult> results = EntityStoreIT.parallel(8, () -> {
            ResolvedClaim c = relation(project, "LICENSED_UNDER", licenses.get(counter.getAndIncrement()), POSITIVE);
            return apply(a, claim(0, c));
        });
        assertThat(results.stream().mapToInt(r -> r.outcomes(Outcome.CLAIM_CREATED).size()).sum()).isEqualTo(1);
        assertThat(results.stream().mapToInt(r -> r.outcomes(Outcome.CLAIM_DISPUTED).size()).sum()).isEqualTo(7);
        assertThat(count("MATCH (c:Claim {brainId: 'cl-a', predicate: 'LICENSED_UNDER', status: 'ACTIVE'})"
                + " RETURN count(c)", Map.of())).isEqualTo(1);
        assertThat(count("MATCH (x:Conflict {brainId: 'cl-a', kind: 'CONTRADICTION'}) RETURN count(x)", Map.of()))
                .isEqualTo(7);
        assertThat(count("MATCH ()-[r:LICENSED_UNDER {brainId: 'cl-a'}]->() RETURN count(r)", Map.of())).isEqualTo(1);
        assertThat(verifier.verify(a)).isEmpty();
    }
}
