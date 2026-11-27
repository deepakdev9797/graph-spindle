package io.graphspindle.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractIngestionIT;
import org.junit.jupiter.api.Test;

/** {@code GET /api/v1/system/info}: needs a key, reports provider, model and the data-transfer notice. */
class SystemInfoIT extends AbstractIngestionIT {

    @Test
    void systemInfoReportsProviderAndDataTransfer() {
        assertThat(anonymous().get("/api/v1/system/info").status()).isEqualTo(401);
        var r = api().get("/api/v1/system/info");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("name").asString()).isEqualTo("GraphSpindle");
        assertThat(r.body().get("version").asString()).isNotBlank();
        assertThat(r.body().get("vocabularyProfile").asString()).isNotBlank();
        // the test double is an unknown provider without a host: conservatively reported as remote
        assertThat(r.body().get("llm").get("provider").asString()).isNotBlank();
        assertThat(r.body().get("dataLeavesMachine").asBoolean()).isTrue();
        assertThat(r.body().get("dataTransferNotice").asString()).contains("sent to the LLM provider");
        assertThat(r.body().toString()).doesNotContainIgnoringCase("api-key").doesNotContain("password");
        // a READ key may read it too
        String key = api().post("/api/v1/brains/dev/api-keys", "{\"name\":\"r\",\"permission\":\"READ\"}").body()
                .get("apiKey").asString();
        assertThat(anonymous().withKey(key).get("/api/v1/system/info").status()).isEqualTo(200);
    }
}
