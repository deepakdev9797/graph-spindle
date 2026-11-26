package io.graphspindle.graph;

import static io.graphspindle.common.Polarity.NEGATIVE;
import static io.graphspindle.common.Polarity.POSITIVE;
import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.common.BrainScope;
import io.graphspindle.common.PartialDate;
import io.graphspindle.common.Polarity;
import io.graphspindle.graph.ApplyResult.Outcome;
import io.graphspindle.ingestion.validation.PlanCommand;
import io.graphspindle.ingestion.validation.PlanCommand.RetractClaimCommand;
import io.graphspindle.ingestion.validation.PlanCommand.SupersedeClaimCommand;
import io.graphspindle.ingestion.validation.ResolvedClaim;
import io.graphspindle.ingestion.validation.ResolvedEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectionIT extends AbstractGraphIT {

    BrainScope a;
    List<ResolvedEntity.Existing> projects = new ArrayList<>();
    List<ResolvedEntity.Existing> techs = new ArrayList<>();
    List<ResolvedEntity.Existing> standards = new ArrayList<>();

    @BeforeEach
    void entities() {
        a = brain("pr-a");
        projects.clear();
        techs.clear();
        standards.clear();
        List<PlanCommand> creates = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            creates.add(create(creates.size(), "p" + i, "Project", "Project " + i));
        }
        for (int i = 0; i < 4; i++) {
            creates.add(create(creates.size(), "t" + i, "Technology", "Tech " + i));
        }
        for (int i = 0; i < 3; i++) {
            creates.add(create(creates.size(), "s" + i, "Standard", "Standard " + i));
        }
        ApplyResult r = apply(a, creates.toArray(PlanCommand[]::new));
        for (int i = 0; i < 3; i++) {
            projects.add(existing(r.refs().get("p" + i), "Project"));
            standards.add(existing(r.refs().get("s" + i), "Standard"));
        }
        for (int i = 0; i < 4; i++) {
            techs.add(existing(r.refs().get("t" + i), "Technology"));
        }
    }

    long edges(String type, String claimId) {
        return count("MATCH (:Entity {brainId: 'pr-a'})-[r {brainId: 'pr-a', claimId: $c}]->(:Entity {brainId: 'pr-a'})"
                + " WHERE type(r) = $t RETURN count(r)", Map.of("c", claimId, "t", type));
    }

    @Test
    void activeRelationClaimIsProjectedAndSupersedeMovesTheEdge() {
        String c1 = apply(a, claim(0, relation(projects.get(0), "LICENSED_UNDER", standards.get(0), POSITIVE)))
                .outcomes(Outcome.CLAIM_CREATED).get(0).claimId();
        assertThat(edges("LICENSED_UNDER", c1)).isEqualTo(1);
        String c2 = apply(a, new SupersedeClaimCommand(0, c1, true, "changed",
                relation(projects.get(0), "LICENSED_UNDER", standards.get(1), POSITIVE)))
                .outcomes(Outcome.CLAIM_CREATED).get(0).claimId();
        assertThat(edges("LICENSED_UNDER", c1)).isZero();
        assertThat(edges("LICENSED_UNDER", c2)).isEqualTo(1);
        assertThat(verifier.verify(a)).isEmpty();
    }

    @Test
    void attributeClaimSetsAttrPropertyAndRetractionRemovesIt() {
        String c = apply(a, claim(0, attribute(techs.get(0), "category", "DATABASE")))
                .outcomes(Outcome.CLAIM_CREATED).get(0).claimId();
        assertThat(reader.entity(a, techs.get(0).entityId()).orElseThrow().attributes())
                .containsEntry("category", "DATABASE");
        apply(a, new RetractClaimCommand(0, c, "wrong", "evidence"));
        assertThat(reader.entity(a, techs.get(0).entityId()).orElseThrow().attributes()).doesNotContainKey("category");
        assertThat(verifier.verify(a)).isEmpty();
    }

    @Test
    void negativeClaimIsNeverProjected() {
        ApplyResult r = apply(a, claim(0, relation(projects.get(0), "USES", techs.get(0), NEGATIVE)));
        String c = r.outcomes(Outcome.CLAIM_CREATED).get(0).claimId();
        assertThat(reader.claim(a, c).orElseThrow().active()).isTrue();
        assertThat(count("MATCH ()-[r:USES {brainId: 'pr-a'}]->() RETURN count(r)", Map.of())).isZero();
        // "we stopped using X in 2025": the positive claim is superseded by a NEGATIVE one with validFrom
        String pos = apply(a, claim(0, relation(projects.get(1), "USES", techs.get(1), POSITIVE)))
                .outcomes(Outcome.CLAIM_CREATED).get(0).claimId();
        apply(a, new SupersedeClaimCommand(0, pos, true, "stopped using it",
                relation(projects.get(1), "USES", techs.get(1), NEGATIVE, 0.9, PartialDate.parse("2025").orElseThrow())));
        assertThat(reader.claim(a, pos).orElseThrow().validTo()).isEqualTo("2025");
        assertThat(count("MATCH ()-[r:USES {brainId: 'pr-a'}]->() RETURN count(r)", Map.of())).isZero();
        assertThat(verifier.verify(a)).isEmpty();
    }

    @Test
    void verifierDetectsInjectedDrift() {
        String c = apply(a, claim(0, relation(projects.get(0), "USES", techs.get(0), POSITIVE)))
                .outcomes(Outcome.CLAIM_CREATED).get(0).claimId();
        write("MATCH ()-[r {brainId: 'pr-a', claimId: $c}]->() DELETE r", Map.of("c", c));
        write("MATCH (n:Entity {brainId: 'pr-a', id: $id}) SET n.attr_category = 'TOOL'",
                Map.of("id", techs.get(1).entityId()));
        assertThat(verifier.verify(a)).extracting(ProjectionVerifier.Drift::kind)
                .containsExactlyInAnyOrder("EDGE_COUNT", "ATTRIBUTE");
    }

    @Test
    void randomSequenceOf200OperationsLeavesNoDrift() {
        Random random = new Random(20261126L);
        String[] categories = {"LANGUAGE", "FRAMEWORK", "DATABASE", "TOOL"};
        for (int i = 0; i < 200; i++) {
            List<ClaimView> known = query("MATCH (c:Claim {brainId: 'pr-a'}) WHERE c.status IN ['ACTIVE','DISPUTED']"
                    + " RETURN c.id AS id ORDER BY c.id", Map.of()).stream()
                    .map(m -> reader.claim(a, (String) m.get("id")).orElseThrow()).toList();
            int kind = random.nextInt(6);
            PlanCommand cmd;
            if (kind >= 4 && !known.isEmpty()) {
                ClaimView target = known.get(random.nextInt(known.size()));
                if (kind == 4 || !target.active()) {
                    cmd = new RetractClaimCommand(0, target.id(), "random retract", "evidence");
                } else {
                    cmd = new SupersedeClaimCommand(0, target.id(), random.nextBoolean(), "random supersede",
                            replacementFor(target, random, categories));
                }
            } else {
                Polarity polarity = random.nextInt(5) == 0 ? NEGATIVE : POSITIVE;
                ResolvedEntity.Existing p = projects.get(random.nextInt(projects.size()));
                cmd = claim(0, switch (random.nextInt(3)) {
                    case 0 -> relation(p, "USES", techs.get(random.nextInt(techs.size())), polarity);
                    case 1 -> relation(p, "LICENSED_UNDER", standards.get(random.nextInt(standards.size())), polarity);
                    default -> attribute(techs.get(random.nextInt(techs.size())), "category",
                            categories[random.nextInt(categories.length)]);
                });
            }
            apply(a, cmd);
        }
        assertThat(verifier.verify(a)).isEmpty();
        assertThat(count("MATCH (c:Claim {brainId: 'pr-a', status: 'ACTIVE'}) WHERE c.slotKey IS NOT NULL"
                + " WITH c.slotKey AS k, count(*) AS n WHERE n > 1 RETURN count(k)", Map.of())).isZero();
        assertThat(count("MATCH (c:Claim {brainId: 'pr-a'}) RETURN count(c)", Map.of())).isGreaterThan(50);
        assertThat(count("MATCH (c:Claim {brainId: 'pr-a', status: 'SUPERSEDED'}) RETURN count(c)", Map.of()))
                .isPositive();
        assertThat(count("MATCH (c:Claim {brainId: 'pr-a', status: 'DISPUTED'}) RETURN count(c)", Map.of()))
                .isPositive();
    }

    private ResolvedClaim replacementFor(ClaimView target, Random random, String[] categories) {
        ResolvedEntity.Existing subject = existing(target.subjectId(),
                reader.entity(a, target.subjectId()).orElseThrow().type());
        if (!target.relation()) {
            return attribute(subject, target.predicate(), categories[random.nextInt(categories.length)]);
        }
        List<ResolvedEntity.Existing> objects = "USES".equals(target.predicate()) ? techs : standards;
        return relation(subject, target.predicate(), objects.get(random.nextInt(objects.size())),
                random.nextInt(4) == 0 ? NEGATIVE : POSITIVE);
    }
}
