package io.graphspindle.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractIngestionIT;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * M7 acceptance: an LLM supersede (or retraction) of a MANUAL claim leaves it ACTIVE and opens a
 * PROTECTED_OVERRIDE conflict, even with correction language and {@code explicitCorrection}.
 */
class ProtectedOverrideIT extends AbstractIngestionIT {

    static final String CORRECTION = "Correction: GraphSpindle is actually GPL-3.0 licensed.";

    private void manualMit(String brain) {
        String project = k.entity(brain, "Project", "GraphSpindle");
        k.relation(brain, project, "LICENSED_UNDER", k.entity(brain, "Standard", "MIT License", "MIT"));
    }

    @Test
    void llmSupersedeOfAManualClaimOpensAProtectedOverride() {
        manualMit("dev");
        script(CORRECTION, List.of("GraphSpindle:Project", "GPL-3.0:Standard"), """
                [{"op":"create_entity","ref":"g","type":"Standard","name":"GNU General Public License v3.0",
                  "aliases":["GPL-3.0"]},
                 {"op":"supersede_claim","claimId":"@claim:GraphSpindle|LICENSED_UNDER","explicitCorrection":true,
                  "reason":"Corrected license","replacement":{"subject":{"id":"@entity:GraphSpindle"},
                  "predicate":"LICENSED_UNDER","object":{"ref":"g"},"statement":"GraphSpindle is licensed under GPL-3.0.",
                  "evidence":"GraphSpindle is actually GPL-3.0 licensed","confidence":0.9}}]""");
        JsonNode r = ingestText("dev", CORRECTION, "");
        assertThat(r.get("accepted").toString()).contains("PROTECTED_OVERRIDE").doesNotContain("CLAIM_SUPERSEDED");
        assertThat(claimStates("dev", "GraphSpindle", "LICENSED_UNDER"))
                .containsExactly("MIT License=ACTIVE", "GNU General Public License v3.0=DISPUTED");
        assertThat(projected("dev", "GraphSpindle", "LICENSED_UNDER")).containsExactly("MIT License");
        List<String> open = openConflicts("dev", "PROTECTED_OVERRIDE");
        assertThat(open).hasSize(1);
        assertThat(openConflicts("dev", "CONTRADICTION")).isEmpty();

        // the user may accept it explicitly: then the manual claim is superseded
        assertThat(resolve("dev", open.get(0), "ACCEPT_INCOMING").status()).isEqualTo(200);
        assertThat(projected("dev", "GraphSpindle", "LICENSED_UNDER")).containsExactly(
                "GNU General Public License v3.0");
        assertOtherBrainUntouched();
    }

    @Test
    void llmRetractionOfAManualClaimOpensAProtectedOverride() {
        manualMit("dev");
        String text = "Correction: the MIT license of GraphSpindle was wrong.";
        script(text, List.of("GraphSpindle:Project", "MIT:Standard"), """
                [{"op":"retract_claim","claimId":"@claim:GraphSpindle|LICENSED_UNDER","reason":"was wrong",
                  "evidence":"the MIT license of GraphSpindle was wrong"}]""");
        JsonNode r = ingestText("dev", text, "");
        assertThat(r.get("accepted").toString()).contains("PROTECTED_OVERRIDE").doesNotContain("CLAIM_RETRACTED");
        assertThat(claimStates("dev", "GraphSpindle", "LICENSED_UNDER")).containsExactly("MIT License=ACTIVE");
        assertThat(openConflicts("dev", "PROTECTED_OVERRIDE")).hasSize(1);
        assertOtherBrainUntouched();
    }
}
