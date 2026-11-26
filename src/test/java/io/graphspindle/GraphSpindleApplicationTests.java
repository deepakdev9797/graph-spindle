package io.graphspindle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
