package io.graphspindle.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every Cypher statement constant contains {@code $brainId} (ADR-004), except schema statements and
 * the allowlisted brain listing; and no Cypher text exists anywhere outside the graph module's
 * {@code *Statements} classes.
 */
class CypherScopingTest {

    static final String INTERNAL = "io.graphspindle.graph.internal.";
    static final List<String> STATEMENT_CLASSES = List.of("BrainStatements", "EntityStatements", "AliasStatements",
            "ClaimStatements", "ProjectionStatements", "IngestionStatements", "SourceStatements", "ConflictStatements",
            "VerifierStatements", "KnowledgeStatements", "RetrievalStatements");
    static final Set<String> ALLOWLIST = Set.of("BrainStatements.LIST_ALL");
    static final Pattern CYPHER = Pattern.compile(
            "\\b(MATCH|MERGE|CREATE) \\(|DETACH DELETE|CREATE (CONSTRAINT|INDEX|FULLTEXT)|CALL db\\.|\\bRETURN [a-z]");

    @Test
    void everyStatementIsBrainScoped() throws Exception {
        int checked = 0;
        for (String name : STATEMENT_CLASSES) {
            for (Field f : Class.forName(INTERNAL + name).getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) {
                    continue;
                }
                f.setAccessible(true);
                String cypher = (String) f.get(null);
                checked++;
                if (!ALLOWLIST.contains(name + "." + f.getName())) {
                    assertThat(cypher).as(name + "." + f.getName()).contains("$brainId");
                }
            }
        }
        assertThat(checked).isGreaterThan(40);
    }

    @Test
    void schemaStatementsAreTheOnlyOtherUnscopedOnes() throws Exception {
        Class<?> schema = Class.forName(INTERNAL + "SchemaStatements");
        for (Field f : schema.getDeclaredFields()) {
            assertThat(f.getType()).as(f.getName()).isNotEqualTo(String.class);
        }
    }

    @Test
    void noCypherOutsideStatementClasses() throws IOException {
        Path root = Path.of("src", "main", "java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String file = p.getFileName().toString();
                boolean statements = p.toString().contains("graph" + java.io.File.separator + "internal")
                        && (file.endsWith("Statements.java"));
                if (statements) {
                    continue;
                }
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                        continue;
                    }
                    if (CYPHER.matcher(line).find()) {
                        offenders.add(p + ":" + (i + 1) + ": " + line);
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}
