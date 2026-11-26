package io.graphspindle.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractIngestionIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * M7 acceptance: "we no longer use Kafka" supersedes the USES claim with a NEGATIVE claim and the
 * projection edge is removed; a stated end date sets {@code validTo} on the old claim (SCHEMA §9.4).
 * A retraction without correction language is not applied (ADR-030).
 */
class NegationIT extends AbstractIngestionIT {

    static final String USES = "GraphSpindle uses Kafka.";

    private void seedUsesKafka() {
        script(USES, List.of("GraphSpindle:Project", "Kafka:Technology"), """
                [{"op":"create_entity","ref":"p","type":"Project","name":"GraphSpindle"},
                 {"op":"create_entity","ref":"t","type":"Technology","name":"Kafka"},
                 {"op":"assert_claim","subject":{"ref":"p"},"predicate":"USES","object":{"ref":"t"},
                  "statement":"GraphSpindle uses Kafka.","evidence":"GraphSpindle uses Kafka","confidence":0.9}]""");
        ingestText("dev", USES, "");
        assertThat(projected("dev", "GraphSpindle", "USES")).containsExactly("Kafka");
    }

    private void scriptNegation(String text, String evidence, String validFrom) {
        script(text, List.of("GraphSpindle:Project", "Kafka:Technology"), """
                [{"op":"supersede_claim","claimId":"@claim:GraphSpindle|USES","explicitCorrection":true,
                  "reason":"no longer used","replacement":{"subject":{"id":"@entity:GraphSpindle"},"predicate":"USES",
                  "object":{"id":"@entity:Kafka"},"polarity":"NEGATIVE","statement":"GraphSpindle does not use Kafka.",
                  "evidence":%s,"confidence":0.9%s}}]""".formatted(JSON.writeValueAsString(evidence),
                validFrom == null ? "" : ",\"validFrom\":\"" + validFrom + "\""));
    }

    @Test
    void noLongerSupersedesWithANegativeClaimAndRemovesTheEdge() {
        seedUsesKafka();
        String text = "We no longer use Kafka in GraphSpindle.";
        scriptNegation(text, "We no longer use Kafka in GraphSpindle", null);
        JsonNode r = ingestText("dev", text, "");
        assertThat(r.get("accepted").toString()).contains("CLAIM_SUPERSEDED");
        assertThat(claimStates("dev", "GraphSpindle", "USES")).containsExactly("Kafka=SUPERSEDED", "Kafka(neg)=ACTIVE");
        assertThat(projected("dev", "GraphSpindle", "USES")).isEmpty();
        assertThat(openConflicts("dev", "CONTRADICTION")).isEmpty();
        assertOtherBrainUntouched();
    }

    @Test
    void aStatedEndSetsValidToOnTheOldClaim() {
        seedUsesKafka();
        String text = "GraphSpindle no longer uses Kafka since 2025.";
        scriptNegation(text, "GraphSpindle no longer uses Kafka since 2025", "2025");
        ingestText("dev", text, "");
        List<Map<String, Object>> rows = query("""
                MATCH (c:Claim {brainId:'dev', predicate:'USES'}) RETURN c.polarity AS p, c.status AS s,
                c.validFrom AS f, c.validTo AS t ORDER BY c.recordedAt""", Map.of());
        assertThat(rows).extracting(m -> m.get("p") + "/" + m.get("s") + "/" + m.get("f") + "/" + m.get("t"))
                .containsExactly("POSITIVE/SUPERSEDED/null/2025", "NEGATIVE/ACTIVE/2025/null");
        assertThat(projected("dev", "GraphSpindle", "USES")).isEmpty();
    }

    @Test
    void aRetractionWithoutCorrectionLanguageIsNotApplied() {
        seedUsesKafka();
        String text = "Kafka is a log for GraphSpindle events.";
        script(text, List.of("GraphSpindle:Project", "Kafka:Technology"), """
                [{"op":"retract_claim","claimId":"@claim:GraphSpindle|USES","reason":"unclear",
                  "evidence":"Kafka is a log for GraphSpindle events"}]""");
        JsonNode r = ingestText("dev", text, "");
        assertThat(r.get("adjusted").toString()).as(r.toString()).contains("RETRACTION_DROPPED");
        assertThat(claimStates("dev", "GraphSpindle", "USES")).containsExactly("Kafka=ACTIVE");
        assertThat(projected("dev", "GraphSpindle", "USES")).containsExactly("Kafka");

        // with correction language the same retraction is applied
        String corrected = "Correction: GraphSpindle never used Kafka, that was a mistake.";
        script(corrected, List.of("GraphSpindle:Project", "Kafka:Technology"), """
                [{"op":"retract_claim","claimId":"@claim:GraphSpindle|USES","reason":"mistake",
                  "evidence":"GraphSpindle never used Kafka, that was a mistake"}]""");
        assertThat(ingestText("dev", corrected, "").get("accepted").toString()).contains("CLAIM_RETRACTED");
        assertThat(projected("dev", "GraphSpindle", "USES")).isEmpty();
        assertOtherBrainUntouched();
    }
}
