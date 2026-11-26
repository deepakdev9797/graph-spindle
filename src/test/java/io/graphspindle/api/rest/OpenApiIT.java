package io.graphspindle.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

class OpenApiIT extends AbstractNeo4jIT {

    @Test
    void apiDocsAreServed() {
        ResponseEntity<String> response = RestClient.create(baseUrl()).get().uri("/v3/api-docs").retrieve()
                .toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"openapi\"");
    }
}
