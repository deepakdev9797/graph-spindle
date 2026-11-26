package io.graphspindle.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractIngestionIT;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * M7 acceptance: under {@code ALWAYS_FLAG} an explicit correction is flagged and not applied; under
 * {@code LATEST_WINS} an implicit contradiction from the same principal supersedes (never a MANUAL claim).
 */
class PolicyConfigIT extends AbstractIngestionIT {

    static final String MIT = "GraphSpindle is MIT licensed.";
    static final String CORRECTION = "Correction: GraphSpindle is actually GPL-3.0 licensed.";
    static final String GPL = "GraphSpindle is GPL-3.0 licensed.";

    private void policy(String brain, String policy) {
        assertThat(api().patch("/api/v1/brains/" + brain, "{\"config\":{\"conflictPolicy\":\"" + policy + "\"}}", null)
                .status()).isEqualTo(200);
    }

    private void seedMit(String brain) {
        script(MIT, List.of("GraphSpindle:Project", "MIT:Standard"), """
                [{"op":"create_entity","ref":"p","type":"Project","name":"GraphSpindle"},
                 {"op":"create_entity","ref":"m","type":"Standard","name":"MIT License","aliases":["MIT"]},
                 {"op":"assert_claim","subject":{"ref":"p"},"predicate":"LICENSED_UNDER","object":{"ref":"m"},
                  "statement":"GraphSpindle is licensed under MIT.","evidence":"GraphSpindle is MIT licensed",
                  "confidence":0.9}]""");
        ingestText(brain, MIT, "?force=true");
    }

    private void scriptGpl(String text, String op) {
        String claim = """
                {"subject":{"id":"@entity:GraphSpindle"},"predicate":"LICENSED_UNDER","object":{"ref":"g"},
                 "statement":"GraphSpindle is licensed under GPL-3.0.","evidence":"GraphSpindle is %sGPL-3.0 licensed",
                 "confidence":0.9}""".formatted(text.contains("actually") ? "actually " : "");
        String operation = op.equals("supersede")
                ? "{\"op\":\"supersede_claim\",\"claimId\":\"@claim:GraphSpindle|LICENSED_UNDER\",\"explicitCorrection\":"
                        + "true,\"reason\":\"corrected\",\"replacement\":" + claim + "}"
                : "{\"op\":\"assert_claim\"," + claim.strip().substring(1);
        script(text, List.of("GraphSpindle:Project", "GPL-3.0:Standard"), """
                [{"op":"create_entity","ref":"g","type":"Standard","name":"GNU General Public License v3.0",
                  "aliases":["GPL-3.0"]},%s]""".formatted(operation));
    }

    @Test
    void alwaysFlagFlagsAnExplicitCorrection() {
        k.brain("flag");
        policy("flag", "ALWAYS_FLAG");
        seedMit("flag");
        scriptGpl(CORRECTION, "supersede");
        JsonNode r = ingestText("flag", CORRECTION, "");
        assertThat(r.get("accepted").toString()).contains("CLAIM_DISPUTED").doesNotContain("CLAIM_SUPERSEDED");
        assertThat(claimStates("flag", "GraphSpindle", "LICENSED_UNDER"))
                .containsExactly("MIT License=ACTIVE", "GNU General Public License v3.0=DISPUTED");
        assertThat(openConflicts("flag", "CONTRADICTION")).hasSize(1);

        // the same correction in a brain with the default policy is applied
        seedMit("dev");
        scriptGpl(CORRECTION, "supersede");
        ingestText("dev", CORRECTION, "?force=true");
        assertThat(projected("dev", "GraphSpindle", "LICENSED_UNDER")).containsExactly(
                "GNU General Public License v3.0");
        assertOtherBrainUntouched();
    }

    @Test
    void latestWinsSupersedesAnImplicitContradictionFromTheSamePrincipal() {
        k.brain("latest");
        policy("latest", "LATEST_WINS");
        seedMit("latest");
        scriptGpl(GPL, "assert");
        JsonNode r = ingestText("latest", GPL, "");
        assertThat(r.get("accepted").toString()).contains("CLAIM_SUPERSEDED");
        assertThat(claimStates("latest", "GraphSpindle", "LICENSED_UNDER"))
                .containsExactly("MIT License=SUPERSEDED", "GNU General Public License v3.0=ACTIVE");
        assertThat(openConflicts("latest", "CONTRADICTION")).isEmpty();

        // a supersede the engine downgrades (no correction language) also wins under LATEST_WINS
        String back = "GraphSpindle is MIT licensed again.";
        script(back, List.of("GraphSpindle:Project", "MIT:Standard"), """
                [{"op":"supersede_claim","claimId":"@claim:GraphSpindle|LICENSED_UNDER","explicitCorrection":true,
                  "reason":"MIT","replacement":{"subject":{"id":"@entity:GraphSpindle"},"predicate":"LICENSED_UNDER",
                  "object":{"id":"@entity:MIT License"},"statement":"GraphSpindle is licensed under MIT.",
                  "evidence":"GraphSpindle is MIT licensed again","confidence":0.9}}]""");
        ingestText("latest", back, "");
        assertThat(projected("latest", "GraphSpindle", "LICENSED_UNDER")).containsExactly("MIT License");

        // the default policy flags the same implicit contradiction
        seedMit("dev");
        scriptGpl(GPL, "assert");
        ingestText("dev", GPL, "?force=true");
        assertThat(projected("dev", "GraphSpindle", "LICENSED_UNDER")).containsExactly("MIT License");
        assertThat(openConflicts("dev", "CONTRADICTION")).hasSize(1);
        assertOtherBrainUntouched();
    }

    @Test
    void latestWinsNeverSupersedesAManualClaim() {
        k.brain("latest");
        policy("latest", "LATEST_WINS");
        String project = k.entity("latest", "Project", "GraphSpindle");
        k.relation("latest", project, "LICENSED_UNDER", k.entity("latest", "Standard", "MIT License", "MIT"));
        scriptGpl(GPL, "assert");
        ingestText("latest", GPL, "");
        assertThat(projected("latest", "GraphSpindle", "LICENSED_UNDER")).containsExactly("MIT License");
        assertThat(openConflicts("latest", "CONTRADICTION")).hasSize(1);
        assertOtherBrainUntouched();
    }
}
