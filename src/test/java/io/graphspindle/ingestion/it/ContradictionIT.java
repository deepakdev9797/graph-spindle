package io.graphspindle.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractIngestionIT;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * M7 acceptance, SCHEMA §10.4: GPL arrives while MIT is active. The GPL claim is DISPUTED with an
 * OPEN CONTRADICTION conflict and retrieval still shows MIT. KEEP_EXISTING retracts GPL;
 * ACCEPT_INCOMING supersedes MIT. Brain {@code other} is never touched.
 */
class ContradictionIT extends AbstractIngestionIT {

    static final String MIT = "GraphSpindle is MIT licensed.";
    static final String GPL = "GraphSpindle is GPL-3.0 licensed.";

    void seedMit() {
        script(MIT, List.of("GraphSpindle:Project", "MIT:Standard"), """
                [{"op":"create_entity","ref":"p","type":"Project","name":"GraphSpindle"},
                 {"op":"create_entity","ref":"m","type":"Standard","name":"MIT License","aliases":["MIT"]},
                 {"op":"assert_claim","subject":{"ref":"p"},"predicate":"LICENSED_UNDER","object":{"ref":"m"},
                  "statement":"GraphSpindle is licensed under MIT.","evidence":"GraphSpindle is MIT licensed",
                  "confidence":0.9}]""");
        ingestText("dev", MIT, "");
    }

    /** §10.4: the model sees no correction language and emits a plain assertion. */
    void scriptGpl() {
        script(GPL, List.of("GraphSpindle:Project", "GPL-3.0:Standard"), """
                [{"op":"create_entity","ref":"g","type":"Standard","name":"GNU General Public License v3.0",
                  "aliases":["GPL-3.0"]},
                 {"op":"assert_claim","subject":{"id":"@entity:GraphSpindle"},"predicate":"LICENSED_UNDER",
                  "object":{"ref":"g"},"statement":"GraphSpindle is licensed under GPL-3.0.",
                  "evidence":"GraphSpindle is GPL-3.0 licensed","confidence":0.9}]""");
    }

    private String gplConflict() {
        scriptGpl();
        JsonNode r = ingestText("dev", GPL, "?force=true");
        assertThat(r.get("accepted").toString()).contains("CLAIM_DISPUTED").doesNotContain("CLAIM_SUPERSEDED");
        List<String> open = openConflicts("dev", "CONTRADICTION");
        assertThat(open).hasSize(1);
        // retrieval keeps showing MIT
        assertThat(projected("dev", "GraphSpindle", "LICENSED_UNDER")).containsExactly("MIT License");
        String project = (String) query("MATCH (n:Entity {brainId:'dev', name:'GraphSpindle'}) RETURN n.id AS id",
                java.util.Map.of()).get(0).get("id");
        JsonNode view = k.get("/api/v1/brains/dev/entities/" + project);
        assertThat(view.get("relationships").toString()).contains("MIT License")
                .doesNotContain("GNU General Public License");
        return open.get(0);
    }

    @Test
    void keepExistingRetractsTheIncomingClaim() {
        seedMit();
        String conflict = gplConflict();
        assertThat(claimStates("dev", "GraphSpindle", "LICENSED_UNDER"))
                .containsExactly("MIT License=ACTIVE", "GNU General Public License v3.0=DISPUTED");

        assertThat(resolve("dev", conflict, "KEEP_EXISTING").status()).isEqualTo(200);
        assertThat(claimStates("dev", "GraphSpindle", "LICENSED_UNDER"))
                .containsExactly("MIT License=ACTIVE", "GNU General Public License v3.0=RETRACTED");
        assertThat(openConflicts("dev", "CONTRADICTION")).isEmpty();
        assertThat(projected("dev", "GraphSpindle", "LICENSED_UNDER")).containsExactly("MIT License");
        assertOtherBrainUntouched();
    }

    @Test
    void acceptIncomingSupersedesTheExistingClaim() {
        seedMit();
        String conflict = gplConflict();
        assertThat(resolve("dev", conflict, "ACCEPT_INCOMING").status()).isEqualTo(200);
        assertThat(claimStates("dev", "GraphSpindle", "LICENSED_UNDER"))
                .containsExactly("MIT License=SUPERSEDED", "GNU General Public License v3.0=ACTIVE");
        assertThat(projected("dev", "GraphSpindle", "LICENSED_UNDER"))
                .containsExactly("GNU General Public License v3.0");
        assertThat(count("MATCH (:Claim {brainId:'dev', status:'ACTIVE'})-[:SUPERSEDES]->(:Claim {brainId:'dev',"
                + " status:'SUPERSEDED'}) RETURN count(*)", java.util.Map.of())).isEqualTo(1);
        // a second resolution of the same conflict is refused
        assertThat(resolve("dev", conflict, "KEEP_EXISTING").status()).isEqualTo(409);
        // the conflict is not visible from the other brain
        assertThat(api().get("/api/v1/brains/other/conflicts/" + conflict).status()).isEqualTo(404);
        assertOtherBrainUntouched();
    }
}
