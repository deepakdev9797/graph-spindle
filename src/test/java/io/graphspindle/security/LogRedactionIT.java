package io.graphspindle.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractIngestionIT;
import io.graphspindle.support.TestKeys;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * IMPLEMENTATION_PLAN M9 {@code LogRedactionTest} (an IT, since it needs the server): the captured
 * log of authenticated, rejected and ingesting requests contains no API key, no {@code Authorization}
 * header value and no ingested content.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogRedactionIT extends AbstractIngestionIT {

    @Test
    void logsContainNoKeysHeadersOrContent(CapturedOutput output) {
        String token = api().post("/api/v1/brains/dev/api-keys", "{\"name\":\"bot\",\"permission\":\"WRITE\"}")
                .body().get("apiKey").asString();
        String secret = ApiKeyTokens.parse(token).orElseThrow().secret();
        String content = "Kestrel-Umbra-7731 is the codename of our private launch";
        anonymous().withKey(token).post("/api/v1/brains/dev/ingestions",
                io.graphspindle.support.KnowledgeApi.json(Map.of("content", content)));
        String wrong = token.substring(0, 21) + "Z".repeat(43);
        anonymous().withKey(wrong).get("/api/v1/brains");
        anonymous().withKey("Bearer-ish " + secret).get("/api/v1/brains");
        anonymous().get("/api/v1/brains");

        String log = output.getAll();
        assertThat(log).contains("rejected API key"); // the rejection itself is logged ...
        assertThat(log).doesNotContain(secret).doesNotContain(token).doesNotContain(wrong.substring(21))
                .doesNotContain(TestKeys.ADMIN).doesNotContain("Kestrel-Umbra-7731")
                .doesNotContainIgnoringCase("authorization: bearer");
    }
}
