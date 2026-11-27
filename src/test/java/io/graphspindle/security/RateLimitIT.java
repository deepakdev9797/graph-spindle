package io.graphspindle.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractMcpIT;
import io.graphspindle.support.ApiClient;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * IMPLEMENTATION_PLAN M9 {@code RateLimitIT}: the 31st ingestion of a key within a minute is 429 over
 * REST, and the budget is shared with MCP {@code remember}; repeated invalid keys from one address are
 * throttled too. Re-declares {@code @SpringBootTest} to run with the default limits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "graphspindle.security.rate-limit.requests-per-minute=1000000",
    "graphspindle.security.rate-limit.failed-auth-per-minute=20"})
class RateLimitIT extends AbstractMcpIT {

    @Test
    void thirtyFirstIngestWithinAMinuteIs429() {
        String token = api().post("/api/v1/brains/dev/api-keys", "{\"name\":\"bot\",\"permission\":\"WRITE\"}")
                .body().get("apiKey").asString();
        ApiClient bot = anonymous().withKey(token);
        for (int i = 0; i < 30; i++) {
            // invalid body: the limiter counts the attempt, nothing reaches the LLM
            assertThat(bot.post("/api/v1/brains/dev/ingestions", "{}").code()).as("ingest %d", i + 1)
                    .isEqualTo("VALIDATION_FAILED");
        }
        var limited = bot.post("/api/v1/brains/dev/ingestions", "{}");
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(limited.body().get("retryAfterSeconds").asLong()).isPositive();
        try (McpSyncClient c = client(token)) {
            c.initialize();
            assertThat(call(c, "remember", "brainId", "dev", "content", "x").code()).isEqualTo("RATE_LIMITED");
        }
        // reads are not ingestions; another principal has its own budget
        assertThat(bot.get("/api/v1/brains/dev/entities").status()).isEqualTo(200);
        assertThat(api().post("/api/v1/brains/dev/ingestions", "{}").code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void repeatedInvalidKeysAreThrottled() {
        ApiClient wrong = anonymous().withKey("gsk_0123456789abcdef_" + "b".repeat(43));
        int unauthorized = 0;
        int limited = 0;
        for (int i = 0; i < 25; i++) {
            int status = wrong.get("/api/v1/brains").status();
            unauthorized += status == 401 ? 1 : 0;
            limited += status == 429 ? 1 : 0;
        }
        assertThat(unauthorized).isEqualTo(20);
        assertThat(limited).isEqualTo(5);
    }
}
