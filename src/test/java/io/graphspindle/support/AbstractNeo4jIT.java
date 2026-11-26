package io.graphspindle.support;

import java.nio.file.Path;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.fleetmanagement.FleetManagementSettings;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for integration tests that need a real Neo4j (INTEGRATION_NOTES §3.1). One in-process
 * harness (bolt only) is started per test JVM and shared by all subclasses, so Spring's context cache
 * keeps working. Subclasses clean their own data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractNeo4jIT {

    protected static final Neo4j NEO4J;

    static {
        NEO4J = Neo4jBuilders.newInProcessBuilder()
                .withWorkingDir(Path.of("target", "neo4j-harness"))
                .withDisabledServer()
                .withConfig(GraphDatabaseSettings.udc_enabled, false)
                .withConfig(FleetManagementSettings.fleet_manager_enabled, false)
                .withConfig(BoltConnector.enable_discovery, false)
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(NEO4J::close, "neo4j-harness-shutdown"));
    }

    @LocalServerPort
    protected int port;

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", () -> NEO4J.boltURI().toString());
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", () -> "");
    }

    protected String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}
