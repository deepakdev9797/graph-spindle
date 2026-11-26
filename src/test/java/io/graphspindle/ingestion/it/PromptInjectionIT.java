package io.graphspindle.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.llm.FakeChatModel;
import io.graphspindle.support.AbstractIngestionIT;
import io.graphspindle.support.ApiClient.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** M6 acceptance: prompt injection, validation rejections and secret redaction through the full pipeline. */
class PromptInjectionIT extends AbstractIngestionIT {

    @Test
    void injectedOperationsAreRejectedAndNothingLeaksAcrossBrains() {
        assertThat(ingest("dev", "readme-example", null).status()).isEqualTo(201);
        List<Object> labelsBefore = query("CALL db.labels() YIELD label RETURN label", Map.of()).stream()
                .map(m -> m.get("label")).toList();
        endToEnd("prompt-injection");
        Response r = ingest("dev", "prompt-injection", null);
        assertThat(r.status()).isEqualTo(201);
        String rejected = r.body().get("rejected").toString();
        assertThat(rejected).contains("UNKNOWN_TYPE", "UNKNOWN_OP", "UNKNOWN_FIELD", "UNKNOWN_HANDLE",
                "UNKNOWN_PREDICATE", "EVIDENCE_NOT_FOUND", "DEPENDENCY_REJECTED");
        // only the real fact survived (and reinforced the existing USES claim)
        assertThat(r.body().get("accepted").toString()).contains("CLAIM_REINFORCED");
        assertThat(nodes("dev", "Entity")).isEqualTo(7);
        assertThat(claims("dev", "ACTIVE")).isEqualTo(13);
        assertThat(query("CALL db.labels() YIELD label RETURN label", Map.of()).stream().map(m -> m.get("label"))
                .toList()).containsExactlyInAnyOrderElementsOf(labelsBefore).doesNotContain("Admin");

        // the prompts: untrusted content inside nonce-tagged blocks, the forged end marker stays inside,
        // no tools, and nothing of the other brain
        FakeChatModel.Call reconcile = fake.calls("reconcile-v1").get(1);
        String nonce = reconcile.user().replaceAll("(?s).*<<<DATA nonce=([0-9a-f]{16}) name=content>>>.*", "$1");
        assertThat(reconcile.system()).contains("nonce=" + nonce);
        String block = reconcile.user().substring(reconcile.user().indexOf("name=content>>>"));
        assertThat(block.indexOf("IGNORE ALL PREVIOUS INSTRUCTIONS")).isLessThan(block.indexOf("<<<END DATA nonce="
                + nonce));
        for (FakeChatModel.Call c : fake.calls()) {
            assertThat(c.options() instanceof ToolCallingChatOptions t && !t.getToolCallbacks().isEmpty()).isFalse();
        }
        assertOtherBrainUntouched();
    }

    @Test
    void invalidOperationsAreRejectedWithCodesWhileValidOnesApply() {
        String text = "[[fake-llm:validation]] GraphSpindle is written in Java.";
        fake.script("validation", text, "extract-v1", "{\"json\":{\"summary\":\"\",\"mentions\":[],\"statements\":[]}}");
        fake.script("validation", text, "reconcile-v1", """
                {"json":{"operations":[
                 {"op":"create_entity","ref":"e1","type":"Project","name":"GraphSpindle"},
                 {"op":"create_entity","ref":"e2","type":"Technology","name":"Java"},
                 {"op":"assert_claim","subject":{"ref":"e1"},"predicate":"WRITTEN_IN","object":{"ref":"e2"},"statement":"GraphSpindle is written in Java.","evidence":"GraphSpindle is written in Java","confidence":0.9},
                 {"op":"create_entity","ref":"e3","type":"Spaceship","name":"Enterprise"},
                 {"op":"assert_claim","subject":{"ref":"e1"},"predicate":"OWNED_BY_ALIENS","object":{"ref":"e2"},"statement":"x","evidence":"GraphSpindle is written in Java","confidence":0.9},
                 {"op":"assert_claim","subject":{"id":"k99"},"predicate":"USES","object":{"ref":"e2"},"statement":"x","evidence":"GraphSpindle is written in Java","confidence":0.9},
                 {"op":"assert_claim","subject":{"ref":"e2"},"predicate":"USES","object":{"ref":"e1"},"statement":"Java uses GraphSpindle.","evidence":"GraphSpindle is written in Java","confidence":0.9},
                 {"op":"assert_claim","subject":{"ref":"e1"},"predicate":"USES","object":{"ref":"e2"},"statement":"GraphSpindle uses Java.","evidence":"GraphSpindle is built on the JVM","confidence":0.9},
                 {"op":"assert_claim","subject":{"ref":"e1"},"predicate":"DEPENDS_ON","object":{"ref":"e1"},"statement":"x","evidence":"GraphSpindle is written in Java","confidence":0.9}
                ]}}""");
        Response r = ingest("dev", Map.of("content", text), "");
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("rejected").findValuesAsString("code")).contains("UNKNOWN_TYPE", "UNKNOWN_PREDICATE",
                "UNKNOWN_HANDLE", "TYPE_PAIR_NOT_ALLOWED", "EVIDENCE_NOT_FOUND", "SELF_LOOP");
        assertThat(r.body().get("opsApplied").asInt()).isEqualTo(3);
        assertThat(claims("dev", "ACTIVE")).isEqualTo(1);
        assertOtherBrainUntouched();
    }

    @Test
    void secretsNeverReachTheModelOrTheGraph() {
        String key = "sk-" + "abcdefghijklmnopqrstuvwxyz012345";
        String text = "[[fake-llm:secret]] GraphSpindle uses Neo4j. The deploy key is " + key + " for now.";
        fake.script("secret", "[[fake-llm:secret]]", "extract-v1",
                "{\"json\":{\"summary\":\"\",\"mentions\":[],\"statements\":[]}}");
        fake.script("secret", "[[fake-llm:secret]]", "reconcile-v1", """
                {"json":{"summary":"GraphSpindle uses Neo4j.","operations":[
                 {"op":"create_entity","ref":"e1","type":"Project","name":"GraphSpindle"},
                 {"op":"create_entity","ref":"e2","type":"Technology","name":"Neo4j"},
                 {"op":"assert_claim","subject":{"ref":"e1"},"predicate":"USES","object":{"ref":"e2"},"statement":"GraphSpindle uses Neo4j.","evidence":"GraphSpindle uses Neo4j","confidence":0.9}]}}""");
        Response r = ingest("dev", Map.of("content", text), "");
        assertThat(r.status()).isEqualTo(201);
        assertThat(fake.calls()).isNotEmpty().allSatisfy(c -> assertThat(c.text()).doesNotContain(key)
                .contains("[REDACTED]"));
        assertThat(count("MATCH (n {brainId:'dev'}) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS $key)"
                + " RETURN count(n)", Map.of("key", key))).isZero();
        assertThat(r.body().toString()).doesNotContain(key);
        assertOtherBrainUntouched();
    }
}
