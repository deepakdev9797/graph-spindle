package io.graphspindle.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Request body cap for streamed bodies (no Content-Length) and strict JSON (QA M2 F5, ADR-022). */
class RequestLimitsIT extends AbstractNeo4jIT {

    @Test
    void chunkedBodyAboveTheCapIsRejectedAsLimitExceeded() throws Exception {
        byte[] head = "{\"id\":\"rl-big\",\"name\":\"n\",\"description\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] filler = "x".repeat(2 * 1024 * 1024).getBytes(StandardCharsets.UTF_8);
        byte[] tail = "\"}".getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/brains"))
                .header("Content-Type", "application/json")
                .header("X-API-Key", io.graphspindle.support.TestKeys.ADMIN)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new SequenceInputStream(Collections.enumeration(
                        List.<InputStream>of(new ByteArrayInputStream(head), new ByteArrayInputStream(filler),
                                new ByteArrayInputStream(tail))))))
                .build();
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("LIMIT_EXCEEDED");
        assertThat(api().get("/api/v1/brains/rl-big").status()).isEqualTo(404);
    }

    @Test
    void bodyBelowTheCapIsAccepted() {
        String desc = "y".repeat(900);
        assertThat(api().post("/api/v1/brains", "{\"id\":\"rl-ok\",\"name\":\"n\",\"description\":\"" + desc + "\"}")
                .status()).isEqualTo(201);
        var dup = api().post("/api/v1/brains", "{\"id\":\"rl-dup\",\"name\":\"n\",\"ownerName\":\"a\",\"ownerName\":\"b\"}");
        assertThat(dup.status()).isEqualTo(400);
        assertThat(dup.code()).isEqualTo("VALIDATION_FAILED");
    }
}
