package io.graphspindle.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import io.graphspindle.support.KnowledgeApi;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Opt-in manual smoke test: the running app (embedded server, in-process Neo4j) ingests the README
 * example through REST against a <b>local</b> Ollama. Never part of a normal build:
 *
 * <pre>
 * ./mvnw -B verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=LiveIngestionSmokeIT \
 *     -Dgraphspindle.live-llm=true [-Dspring.ai.ollama.chat.model=gemma4:12b]
 * </pre>
 */
@EnabledIfSystemProperty(named = "graphspindle.live-llm", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.model.chat=ollama", "spring.ai.ollama.base-url=http://localhost:11434"})
class LiveIngestionSmokeIT extends AbstractNeo4jIT {

    @Value("${spring.ai.ollama.base-url}")
    String baseUrl;

    @Test
    void readmeExampleAgainstLocalOllama() throws Exception {
        assertThat(URI.create(baseUrl).getHost()).isEqualTo("localhost");
        KnowledgeApi k = new KnowledgeApi(api());
        k.brain("dev");
        k.brain("other");
        long t0 = System.currentTimeMillis();
        // the shared test client has a short read timeout; a live ingestion may take up to graphspindle.llm.timeout
        var http = java.net.http.HttpClient.newHttpClient();
        var response = http.send(java.net.http.HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/brains/dev/ingestions"))
                .timeout(java.time.Duration.ofSeconds(180)).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(KnowledgeApi.json(Map.of("requestId", "smoke-1",
                        "content", "I am building GraphSpindle in Java. It uses Neo4j and Spring AI. I decided to use"
                                + " Apache 2.0.")))).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        long ms = System.currentTimeMillis() - t0;
        System.out.println("LIVE-SMOKE status=" + response.statusCode() + " ms=" + ms + " body=" + response.body());
        System.out.println("LIVE-SMOKE entities=" + query("MATCH (e:Entity {brainId:'dev'}) RETURN e.type AS t, e.name"
                + " AS n, [k IN keys(e) WHERE k STARTS WITH 'attr_' | k + '=' + toString(e[k])] AS attrs ORDER BY t, n",
                Map.of()));
        System.out.println("LIVE-SMOKE edges=" + query("MATCH (a:Entity {brainId:'dev'})-[x]->(b:Entity {brainId:'dev'})"
                + " WHERE x.claimId IS NOT NULL RETURN a.name + ' -' + type(x) + '-> ' + b.name AS e ORDER BY e", Map.of()));
        System.out.println("LIVE-SMOKE other=" + count("MATCH (n {brainId:'other'}) RETURN count(n)", Map.of()));
        assertThat(response.statusCode()).isIn(200, 201);
        assertThat(count("MATCH (n {brainId:'other'}) WHERE NOT n:Brain RETURN count(n)", Map.of())).isEqualTo(1);
    }
}
