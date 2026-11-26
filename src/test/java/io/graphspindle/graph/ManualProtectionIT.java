package io.graphspindle.graph;

import static io.graphspindle.support.KnowledgeApi.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.brain.BrainConfig;
import io.graphspindle.brain.NewBrain;
import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ConflictPolicy;
import io.graphspindle.common.Polarity;
import io.graphspindle.graph.ApplyResult.Outcome;
import io.graphspindle.ingestion.validation.PlanCommand.RetractClaimCommand;
import io.graphspindle.ingestion.validation.PlanCommand.SupersedeClaimCommand;
import io.graphspindle.ingestion.validation.PlanCommand.UpdateEntityCommand;
import io.graphspindle.support.KnowledgeApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M5 acceptance: MANUAL knowledge is shown with its origin and protected from the LLM. */
class ManualProtectionIT extends AbstractGraphIT {

    KnowledgeApi k;
    BrainScope dev;
    String gs;
    String apache;
    String mit;

    @BeforeEach
    void setUp() {
        k = new KnowledgeApi(api());
        k.brain("dev");
        k.brain("other");
        dev = BrainScope.of("dev");
        gs = k.entity("dev", "Project", "GraphSpindle");
        apache = k.entity("dev", "Standard", "Apache License 2.0");
        mit = k.entity("dev", "Standard", "MIT License");
    }

    @Test
    void manualClaimShowsItsOriginAndResistsLlmCorrections() {
        String license = k.relation("dev", gs, "LICENSED_UNDER", apache);
        JsonNode claim = k.get("/api/v1/brains/dev/claims/" + license).get("claim");
        assertThat(claim.get("origin").asString()).isEqualTo("MANUAL");
        assertThat(claim.get("createdByPrincipal").asString()).isEqualTo("admin");
        assertThat(k.get("/api/v1/brains/dev/entities/" + gs + "?include=claims").get("text").asString()).contains("MANUAL");

        ApplyResult r = apply(dev, new SupersedeClaimCommand(0, license, true, "llm says MIT",
                relation(existing(gs, "Project"), "LICENSED_UNDER", existing(mit, "Standard"), Polarity.POSITIVE)));
        assertThat(r.outcomes(Outcome.PROTECTED_OVERRIDE)).hasSize(1);
        r = apply(dev, new RetractClaimCommand(0, license, "llm says wrong", "evidence"));
        assertThat(r.outcomes(Outcome.PROTECTED_OVERRIDE)).hasSize(1);
        assertThat(k.get("/api/v1/brains/dev/claims/" + license).get("claim").get("status").asString())
                .isEqualTo("ACTIVE");
        assertThat(k.get("/api/v1/brains/dev/conflicts?kind=PROTECTED_OVERRIDE").get("items").size()).isEqualTo(2);
    }

    @Test
    void manualDescriptionEditLocksTheDescription() {
        var patched = k.raw().patch("/api/v1/brains/dev/entities/" + gs, json(Map.of("description",
                "A knowledge graph engine.")), null);
        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.body().get("descriptionLocked").asBoolean()).isTrue();

        ApplyResult r = apply(dev, new UpdateEntityCommand(0, existing(gs, "Project"), "LLM rewrite", "newer"));
        assertThat(r.outcomes(Outcome.PROTECTED_OVERRIDE)).hasSize(1);
        assertThat(reader.entity(dev, gs).orElseThrow().description()).isEqualTo("A knowledge graph engine.");

        // explicit unlock lets the LLM update it again
        k.raw().patch("/api/v1/brains/dev/entities/" + gs, json(Map.of("descriptionLocked", false)), null);
        assertThat(apply(dev, new UpdateEntityCommand(0, existing(gs, "Project"), "LLM rewrite", "newer"))
                .outcomes(Outcome.ENTITY_UPDATED)).hasSize(1);
        // a rename keeps the old name as an alias
        var renamed = k.raw().patch("/api/v1/brains/dev/entities/" + gs, json(Map.of("name", "Graph Spindle Engine")),
                null);
        assertThat(renamed.body().get("canonicalKey").asString()).isEqualTo("project:graph spindle engine");
        assertThat(reader.entitiesByAlias(dev, "graphspindle")).extracting(EntityView::id).containsExactly(gs);
        assertThat(k.raw().patch("/api/v1/brains/dev/entities/" + gs, json(Map.of("name", "MIT License")), null)
                .status()).isEqualTo(200); // other type: no key collision
        String other = k.entity("dev", "Project", "Other");
        assertThat(k.raw().patch("/api/v1/brains/dev/entities/" + other, json(Map.of("name", "mit license")), null)
                .code()).isEqualTo("CONFLICT_STATE");
    }

    @Test
    void manualAssertionConfirmsAnLlmClaim() {
        String llm = apply(dev, claim(0, relation(existing(gs, "Project"), "LICENSED_UNDER",
                existing(apache, "Standard"), Polarity.POSITIVE))).outcomes(Outcome.CLAIM_CREATED).get(0).claimId();
        assertThat(reader.claim(dev, llm).orElseThrow().origin()).isEqualTo("LLM");
        var r = k.assertRelation("dev", gs, "LICENSED_UNDER", apache);
        assertThat(r.body().get("outcome").asString()).isEqualTo("REINFORCED");
        assertThat(r.body().get("claim").get("id").asString()).isEqualTo(llm);
        assertThat(r.body().get("claim").get("origin").asString()).isEqualTo("MANUAL");
        assertThat(r.body().get("claim").get("confidence").asDouble()).isEqualTo(1.0);
        assertThat(apply(dev, new SupersedeClaimCommand(0, llm, true, "llm", relation(existing(gs, "Project"),
                "LICENSED_UNDER", existing(mit, "Standard"), Polarity.POSITIVE))).outcomes(Outcome.PROTECTED_OVERRIDE))
                .hasSize(1);
    }

    @Test
    void userCorrectionsApplyEvenUnderAlwaysFlag() {
        brains.create(new NewBrain("strict", "strict", null, null, new BrainConfig(ConflictPolicy.ALWAYS_FLAG, null,
                null, null, null, null, null, null, null)));
        String p = k.entity("strict", "Project", "GraphSpindle");
        String a = k.entity("strict", "Standard", "Apache License 2.0");
        String m = k.entity("strict", "Standard", "MIT License");
        String license = k.relation("strict", p, "LICENSED_UNDER", a);
        var r = k.raw().post("/api/v1/brains/strict/claims/" + license + "/supersede", json(Map.of("reason",
                "switch", "replacement", Map.of("objectId", m))));
        assertThat(r.body().get("outcome").asString()).isEqualTo("CREATED");
        assertThat(r.body().get("supersededClaimId").asString()).isEqualTo(license);
        assertThat(count("MATCH (c:Claim {brainId: 'other'}) RETURN count(c)", Map.of())).isZero();
        assertThat(verifier.verify(BrainScope.of("strict"))).isEmpty();
        assertThat(List.of(verifier.verify(dev))).allSatisfy(d -> assertThat(d).isEmpty());
    }
}
