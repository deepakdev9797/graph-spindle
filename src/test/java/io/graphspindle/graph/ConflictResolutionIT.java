package io.graphspindle.graph;

import static io.graphspindle.support.KnowledgeApi.json;
import static io.graphspindle.support.KnowledgeApi.texts;
import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.common.BrainScope;
import io.graphspindle.common.Polarity;
import io.graphspindle.graph.ApplyResult.Outcome;
import io.graphspindle.ingestion.validation.PlanCommand.ProposeMergeCommand;
import io.graphspindle.ingestion.validation.PlanCommand.SupersedeClaimCommand;
import io.graphspindle.support.ApiClient.Response;
import io.graphspindle.support.KnowledgeApi;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Manual conflict resolution (SCHEMA §9.4, ConflictService) for all conflict kinds. */
class ConflictResolutionIT extends AbstractGraphIT {

    KnowledgeApi k;
    BrainScope dev;
    String gs;
    String apache;
    String mit;
    String license;

    @BeforeEach
    void setUp() {
        k = new KnowledgeApi(api());
        k.brain("dev");
        k.brain("other");
        dev = BrainScope.of("dev");
        gs = k.entity("dev", "Project", "GraphSpindle");
        apache = k.entity("dev", "Standard", "Apache License 2.0");
        mit = k.entity("dev", "Standard", "MIT License");
        license = apply(dev, claim(0, relation(existing(gs, "Project"), "LICENSED_UNDER", existing(apache, "Standard"),
                Polarity.POSITIVE))).outcomes(Outcome.CLAIM_CREATED).get(0).claimId();
        k.entity("other", "Project", "GraphSpindle");
    }

    String contradiction() {
        return apply(dev, claim(0, relation(existing(gs, "Project"), "LICENSED_UNDER", existing(mit, "Standard"),
                Polarity.POSITIVE))).outcomes(Outcome.CLAIM_DISPUTED).get(0).conflictId();
    }

    Response resolve(String conflictId, Map<String, Object> body) {
        return k.raw().post("/api/v1/brains/dev/conflicts/" + conflictId + "/resolve", json(body));
    }

    @Test
    void keepExistingRetractsTheIncomingClaim() {
        String conflictId = contradiction();
        assertThat(texts(k.get("/api/v1/brains/dev/conflicts?status=OPEN").get("items"), "id"))
                .containsExactly(conflictId);
        Response r = resolve(conflictId, Map.of("resolution", "KEEP_EXISTING", "note", "MIT was a typo"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("conflict").get("status").asString()).isEqualTo("RESOLVED");
        assertThat(r.body().get("conflict").get("resolution").asString()).isEqualTo("KEEP_EXISTING");
        assertThat(r.body().get("conflict").get("resolvedBy").asString()).isEqualTo("admin");
        assertThat(texts(r.body().get("changedClaims"), "status")).containsExactly("RETRACTED");
        assertThat(reader.claim(dev, license).orElseThrow().active()).isTrue();
        assertThat(resolve(conflictId, Map.of("resolution", "KEEP_EXISTING")).code()).isEqualTo("CONFLICT_STATE");
        assertThat(k.get("/api/v1/brains/dev/conflicts?status=OPEN").get("items").size()).isZero();
        assertThat(verifier.verify(dev)).isEmpty();
    }

    @Test
    void acceptIncomingSupersedesTheExistingClaim() {
        String conflictId = contradiction();
        String incoming = k.get("/api/v1/brains/dev/conflicts/" + conflictId).get("claims").get(1).get("claim")
                .get("id").asString();
        Response r = resolve(conflictId, Map.of("resolution", "ACCEPT_INCOMING"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(reader.claim(dev, license).orElseThrow().status()).isEqualTo("SUPERSEDED");
        ClaimView now = reader.claim(dev, incoming).orElseThrow();
        assertThat(now.active()).isTrue();
        assertThat(now.slotKey()).isNotNull();
        assertThat(texts(k.get("/api/v1/brains/dev/claims/" + incoming + "/history"), "claim.id"))
                .containsExactly(license, incoming);
        assertThat(texts(k.get("/api/v1/brains/dev/entities/" + gs + "/neighbors").get("nodes"), "entity.name"))
                .containsExactly("MIT License");
        // the activated claim is searchable again (its statement is "Project LICENSED_UNDER Standard")
        assertThat(texts(k.get("/api/v1/brains/dev/search?q=licensed_under").get("hits"), "entity.id")).contains(gs);
        assertThat(verifier.verify(dev)).isEmpty();
        assertThat(resolve(contradiction2(), Map.of("resolution", "MERGE")).status()).isEqualTo(400);
    }

    private String contradiction2() {
        return apply(dev, claim(0, relation(existing(gs, "Project"), "LICENSED_UNDER", existing(apache, "Standard"),
                Polarity.POSITIVE))).outcomes(Outcome.CLAIM_DISPUTED).get(0).conflictId();
    }

    @Test
    void protectedOverrideCanBeAcceptedByTheUser() {
        String manual = k.relation("dev", k.entity("dev", "Project", "Manual"), "LICENSED_UNDER", apache);
        String subject = reader.claim(dev, manual).orElseThrow().subjectId();
        ApplyResult r = apply(dev, new SupersedeClaimCommand(0, manual, true, "llm thinks MIT",
                relation(existing(subject, "Project"), "LICENSED_UNDER", existing(mit, "Standard"), Polarity.POSITIVE)));
        String conflictId = r.outcomes(Outcome.PROTECTED_OVERRIDE).get(0).conflictId();
        Response res = resolve(conflictId, Map.of("resolution", "ACCEPT_INCOMING"));
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
        assertThat(reader.claim(dev, manual).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(verifier.verify(dev)).isEmpty();
    }

    @Test
    void keepBothNeedsNonOverlappingRanges() {
        String kafka = k.entity("dev", "Technology", "Kafka");
        var used = k.raw().post("/api/v1/brains/dev/claims", json(Map.of("subjectId", gs, "predicate", "USES",
                "objectId", kafka, "validTo", "2025")));
        assertThat(used.status()).isEqualTo(201);
        var stopped = k.raw().post("/api/v1/brains/dev/claims", json(Map.of("subjectId", gs, "predicate", "USES",
                "objectId", kafka, "polarity", "NEGATIVE")));
        assertThat(stopped.body().get("outcome").asString()).isEqualTo("DISPUTED");
        String conflictId = stopped.body().get("conflictId").asString();
        assertThat(resolve(conflictId, Map.of("resolution", "KEEP_BOTH")).code()).isEqualTo("CONFLICT_STATE");
        Response r = resolve(conflictId, Map.of("resolution", "KEEP_BOTH", "validFrom", "2025"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        JsonNode changed = r.body().get("changedClaims").get(0);
        assertThat(changed.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(changed.get("validFrom").asString()).isEqualTo("2025");
        assertThat(k.get("/api/v1/brains/dev/claims?subjectId=" + gs + "&predicate=USES&status=ACTIVE").get("items")
                .size()).isEqualTo(2);
        assertThat(verifier.verify(dev)).isEmpty();
    }

    @Test
    void duplicateCandidatesMergeOrAreDismissed() {
        String dup = k.entity("dev", "Project", "Graph Spindle");
        String conflictId = apply(dev, new ProposeMergeCommand(0, existing(dup, "Project"), existing(gs, "Project"),
                "spacing")).outcomes(Outcome.CONFLICT_OPENED).get(0).conflictId();
        assertThat(resolve(conflictId, Map.of("resolution", "KEEP_EXISTING")).status()).isEqualTo(400);
        Response r = resolve(conflictId, Map.of("resolution", "MERGE"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.body().get("merge").get("survivor").get("id").asString()).isEqualTo(gs);
        assertThat(r.body().get("conflict").get("status").asString()).isEqualTo("RESOLVED");
        assertThat(r.body().get("conflict").get("resolution").asString()).isEqualTo("MERGE");
        assertThat(reader.entity(dev, dup).orElseThrow().status()).isEqualTo("MERGED");

        String other = k.entity("dev", "Project", "GraphSpindl");
        String second = apply(dev, new ProposeMergeCommand(0, existing(other, "Project"), existing(gs, "Project"),
                "typo?")).outcomes(Outcome.CONFLICT_OPENED).get(0).conflictId();
        Response dismissed = resolve(second, Map.of("resolution", "NOT_DUPLICATE"));
        assertThat(dismissed.body().get("conflict").get("status").asString()).isEqualTo("DISMISSED");
        assertThat(reader.entity(dev, other).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(resolve("0190a3c4-0000-7000-8000-000000000000", Map.of("resolution", "MERGE")).status())
                .isEqualTo(404);
        assertThat(resolve(second, Map.of("resolution", "WHATEVER")).status()).isEqualTo(400);
    }
}
