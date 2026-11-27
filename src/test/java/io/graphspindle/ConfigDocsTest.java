package io.graphspindle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * IMPLEMENTATION_PLAN M10 {@code ConfigDocsTest}: every {@code graphspindle.*} property of
 * application.yml is documented in the README, every {@code GRAPHSPINDLE_*} variable is in
 * .env.example, and the Compose file parses and publishes ports on 127.0.0.1 only (it is not run).
 */
class ConfigDocsTest {

    static final YAMLMapper YAML = YAMLMapper.builder().build();

    @Test
    void everyGraphspindlePropertyIsDocumented() throws Exception {
        JsonNode root = YAML.readTree(Files.readString(Path.of("src/main/resources/application.yml")));
        List<String> keys = new ArrayList<>();
        flatten("graphspindle", root.get("graphspindle"), keys);
        assertThat(keys).hasSizeGreaterThan(15).contains("graphspindle.security.admin-api-key");
        String readme = Files.readString(Path.of("README.md"));
        assertThat(keys.stream().filter(k -> !readme.contains(k)).toList()).as("undocumented properties").isEmpty();
    }

    @Test
    void everyEnvironmentVariableIsInEnvExample() throws Exception {
        TreeSet<String> vars = new TreeSet<>();
        try (var files = Files.list(Path.of("src/main/resources"))) {
            for (Path p : files.filter(f -> f.getFileName().toString().matches("application.*\\.yml")).toList()) {
                Matcher m = Pattern.compile("\\$\\{(GRAPHSPINDLE_[A-Z0-9_]+)").matcher(Files.readString(p));
                while (m.find()) {
                    vars.add(m.group(1));
                }
            }
        }
        String example = Files.readString(Path.of(".env.example"));
        assertThat(vars).hasSizeGreaterThan(20);
        assertThat(vars.stream().filter(v -> !example.contains(v + "=")).toList()).as("missing in .env.example")
                .isEmpty();
        // sanitized: no value for any key or password
        for (String line : example.lines().filter(l -> l.matches("GRAPHSPINDLE_[A-Z_]*(KEY|PASSWORD)=.*")).toList()) {
            assertThat(line).as(line).endsWith("=");
        }
    }

    @Test
    void composeFileParsesAndPublishesOnLoopbackOnly() throws Exception {
        JsonNode compose = YAML.readTree(Files.readString(Path.of("compose.yaml")));
        JsonNode services = compose.get("services");
        assertThat(services.propertyNames()).contains("graphspindle", "neo4j", "ollama");
        List<String> ports = new ArrayList<>();
        services.forEach(s -> s.path("ports").forEach(p -> ports.add(p.asString())));
        assertThat(ports).isNotEmpty().allMatch(p -> p.startsWith("127.0.0.1:"));
        assertThat(services.get("ollama").get("profiles").get(0).asString()).isEqualTo("ollama");
        assertThat(services.get("graphspindle").get("environment").get("GRAPHSPINDLE_ADMIN_API_KEY").asString())
                .contains(":?");
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        assertThat(dockerfile).contains("eclipse-temurin:21-jre", "USER app").containsPattern("(?m)^FROM .* AS build");
    }

    private static void flatten(String prefix, JsonNode node, List<String> out) {
        if (node != null && node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                flatten(prefix + "." + e.getKey(), e.getValue(), out);
            }
        } else {
            out.add(prefix);
        }
    }
}
