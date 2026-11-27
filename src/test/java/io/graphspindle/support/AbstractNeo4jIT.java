package io.graphspindle.support;

import java.nio.file.Path;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.fleetmanagement.FleetManagementSettings;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;
import org.junit.jupiter.api.BeforeEach;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for integration tests that need a real Neo4j (INTEGRATION_NOTES §3.1). One in-process
 * harness (bolt only) is started per test JVM and shared by all subclasses, so Spring's context cache
 * keeps working. Subclasses clean their own data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    // ITs send many requests from one principal; RateLimitIT re-declares @SpringBootTest with the defaults
    "graphspindle.security.rate-limit.requests-per-minute=1000000",
    "graphspindle.security.rate-limit.ingest-per-minute=1000000",
    "graphspindle.security.rate-limit.failed-auth-per-minute=1000000"})
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

    @Autowired
    protected Driver driver;

    @BeforeEach
    void cleanDatabase() {
        try (var session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n").consume();
        }
    }

    /** Runs a read query directly (test assertions only) and returns the rows as maps. */
    protected java.util.List<java.util.Map<String, Object>> query(String cypher, java.util.Map<String, Object> params) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run(cypher, params).list(r -> r.asMap()));
        }
    }

    protected long count(String cypher, java.util.Map<String, Object> params) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run(cypher, params).single().get(0).asLong());
        }
    }

    protected void write(String cypher, java.util.Map<String, Object> params) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher, params).consume());
        }
    }

    /** A client authenticated with the bootstrap admin key ({@link TestKeys#ADMIN}). */
    protected ApiClient api() {
        return new ApiClient(baseUrl(), TestKeys.ADMIN);
    }

    /** A client without any API key. */
    protected ApiClient anonymous() {
        return new ApiClient(baseUrl());
    }

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", () -> NEO4J.boltURI().toString());
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", () -> "");
        registry.add("graphspindle.security.admin-api-key", () -> TestKeys.ADMIN);
    }

    protected String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}
