package io.graphspindle.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HealthIT extends AbstractNeo4jIT {

    @Test
    void healthIsUpWithStatusOnly() {
        ResponseEntity<String> response = RestClient.create(baseUrl()).get().uri("/actuator/health").retrieve()
                .toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = JsonMapper.builder().build().readTree(response.getBody());
        assertThat(body.get("status").asString()).isEqualTo("UP");
        assertThat(body.has("components")).as("no details exposed").isFalse();
    }
}
