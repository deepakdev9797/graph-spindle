package io.graphspindle.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractIngestionIT;
import io.graphspindle.support.ApiClient.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SCHEMA §10.4 with the behaviour the live eval observed (M6_PREP_REPORT §5.1): another agent states
 * "GraphSpindle is GPL-3.0 licensed." without correction words, and the model answers with a
 * supersede_claim marked explicitCorrection. The engine downgrades it (ADR-028): the GPL claim is
 * DISPUTED with an OPEN CONTRADICTION conflict and the existing license stays ACTIVE.
 */
class ContradictionIngestionIT extends AbstractIngestionIT {

    static final String GPL = "GraphSpindle is GPL-3.0 licensed.";

    void scriptGplAsSupersede() {
        fake.script("gpl-supersede", GPL, "extract-v1", "{\"json\":{\"summary\":\"GPL\",\"mentions\":[{\"localRef\":\"m1\","
                + "\"name\":\"GraphSpindle\",\"type\":\"Project\",\"evidence\":\"GraphSpindle is\"}],\"statements\":[{"
                + "\"subjectRef\":\"m1\",\"predicate\":\"LICENSED_UNDER\",\"objectRef\":\"m2\",\"statement\":\"x\","
                + "\"evidence\":\"GraphSpindle is GPL-3.0 licensed\",\"confidence\":0.9}]}}");
        fake.script("gpl-supersede", GPL, "reconcile-v1", """
                {"json":{"summary":"GraphSpindle is GPL-3.0 licensed.","operations":[
                 {"op":"create_entity","ref":"g1","type":"Standard","name":"GNU General Public License v3.0","aliases":["GPL-3.0"]},
                 {"op":"assert_claim","subject":{"ref":"g1"},"predicate":"category","value":"LICENSE","statement":"GPL-3.0 is a license.","evidence":"GPL-3.0 licensed","confidence":0.9},
                 {"op":"supersede_claim","claimId":"@claim:GraphSpindle|LICENSED_UNDER","explicitCorrection":true,"reason":"The license is GPL-3.0",
                  "replacement":{"subject":{"id":"@entity:GraphSpindle"},"predicate":"LICENSED_UNDER","object":{"ref":"g1"},
                   "statement":"GraphSpindle is licensed under GPL-3.0.","evidence":"GraphSpindle is GPL-3.0 licensed","confidence":0.9}}]}}
                """);
    }

    @Test
    void implicitContradictionMislabelledAsCorrectionBecomesAConflict() {
        assertThat(ingest("dev", "readme-example", null).status()).isEqualTo(201);
        scriptGplAsSupersede();
        Map<String, Object> body = Map.of("content", GPL, "speaker", Map.of("type", "Agent", "name", "ci-bot"));
        Response r = ingest("dev", body, "");
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("rejected")).isEmpty();
        assertThat(r.body().get("adjusted").toString()).contains("CORRECTION_DOWNGRADED").contains("SPEAKER_CREATED");
        assertThat(r.body().get("accepted").toString()).contains("CLAIM_DISPUTED")
                .doesNotContain("CLAIM_SUPERSEDED");

        List<Map<String, Object>> licenses = query("""
                MATCH (c:Claim {brainId:'dev', predicate:'LICENSED_UNDER'})-[:OBJECT]->(o:Entity)
                RETURN o.name AS o, c.status AS s ORDER BY c.recordedAt""", Map.of());
        assertThat(licenses).extracting(m -> m.get("o") + "=" + m.get("s"))
                .containsExactly("Apache License 2.0=ACTIVE", "GNU General Public License v3.0=DISPUTED");
        assertThat(query("MATCH (x:Conflict {brainId:'dev'}) RETURN x.kind AS k, x.status AS s", Map.of()))
                .extracting(m -> m.get("k") + "=" + m.get("s")).containsExactly("CONTRADICTION=OPEN");
        // retrieval still shows the existing license
        assertThat(query("MATCH (:Entity {brainId:'dev', name:'GraphSpindle'})-[:LICENSED_UNDER]->(o) RETURN o.name AS o",
                Map.of())).extracting(m -> m.get("o")).containsExactly("Apache License 2.0");
        // the speaker is an Agent created with this ingestion
        assertThat(count("MATCH (a:Entity:Agent {brainId:'dev', name:'ci-bot'}) RETURN count(a)", Map.of())).isEqualTo(1);
        assertThat(fake.calls("reconcile-v1").get(1).user()).contains("ci-bot");

        // the same contradiction again (forced past dedup) reinforces the open dispute instead of a new conflict
        scriptGplAsSupersede();
        Response again = ingest("dev", body, "?force=true");
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.body().get("accepted").toString()).contains("DISPUTE_REINFORCED");
        assertThat(count("MATCH (x:Conflict {brainId:'dev'}) RETURN count(x)", Map.of())).isEqualTo(1);
        assertThat(count("MATCH (a:Entity:Agent {brainId:'dev', name:'ci-bot'}) RETURN count(a)", Map.of())).isEqualTo(1);
        assertOtherBrainUntouched();
    }

    @Test
    void correctionLanguageLetsTheSupersedeThrough() {
        assertThat(ingest("dev", "readme-example", null).status()).isEqualTo(201);
        String text = "Correction: GraphSpindle is actually GPL-3.0 licensed.";
        fake.script("gpl-correction", text, "extract-v1", "{\"json\":{\"summary\":\"\",\"mentions\":[{\"localRef\":\"m1\","
                + "\"name\":\"GraphSpindle\",\"type\":\"Project\",\"evidence\":\"GraphSpindle is\"}],\"statements\":[]}}");
        fake.script("gpl-correction", text, "reconcile-v1", """
                {"json":{"operations":[
                 {"op":"create_entity","ref":"g1","type":"Standard","name":"GNU General Public License v3.0","aliases":["GPL-3.0"]},
                 {"op":"supersede_claim","claimId":"@claim:GraphSpindle|LICENSED_UNDER","explicitCorrection":true,"reason":"Corrected license",
                  "replacement":{"subject":{"id":"@entity:GraphSpindle"},"predicate":"LICENSED_UNDER","object":{"ref":"g1"},
                   "statement":"GraphSpindle is licensed under GPL-3.0.","evidence":"GraphSpindle is actually GPL-3.0 licensed","confidence":0.9}}]}}
                """);
        Response r = ingest("dev", Map.of("content", text), "");
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("accepted").toString()).contains("CLAIM_SUPERSEDED");
        assertThat(query("MATCH (:Entity {brainId:'dev', name:'GraphSpindle'})-[:LICENSED_UNDER]->(o) RETURN o.name AS o",
                Map.of())).extracting(m -> m.get("o")).containsExactly("GNU General Public License v3.0");
        assertOtherBrainUntouched();
    }
}
