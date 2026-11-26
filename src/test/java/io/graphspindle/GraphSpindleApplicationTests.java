package io.graphspindle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/** Context loads without a running Neo4j (the driver is lazy); the Neo4j health contributor and schema initialization are off here. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.health.neo4j.enabled=false", "graphspindle.graph.initialize-schema=false"})
class GraphSpindleApplicationTests {

    @LocalServerPort
    int port;

    @Test
    void healthEndpointIsUp() {
        String body = RestClient.create("http://127.0.0.1:" + port)
                .get().uri("/actuator/health").retrieve().body(String.class);
        assertThat(body).contains("UP");
    }
}
