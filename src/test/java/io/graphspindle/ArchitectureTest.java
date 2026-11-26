package io.graphspindle;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Module boundary rules (ARCHITECTURE §2, IMPLEMENTATION_PLAN M1). */
class ArchitectureTest {

    private static final String OPS = "io.graphspindle.ingestion.ops";
    private static final String VALIDATION = "io.graphspindle.ingestion.validation";

    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.graphspindle");
    }

    @Test
    void onlyGraphModuleTalksToNeo4j() {
        noClasses().that().resideOutsideOfPackage("io.graphspindle.graph..")
                .should().dependOnClassesThat().resideInAPackage("org.neo4j..")
                .because("only the graph module contains Cypher and uses the Neo4j driver")
                .check(classes);
    }

    @Test
    void inboundAdaptersDoNotTouchTheGraphDirectly() {
        noClasses().that().resideInAPackage("io.graphspindle.api..")
                .should().dependOnClassesThat().resideInAnyPackage("io.graphspindle.graph..", "io.graphspindle.llm..")
                .because("controllers and MCP tools depend only on application services")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void llmDoesNotDependOnGraph() {
        noClasses().that().resideInAPackage("io.graphspindle.llm..")
                .should().dependOnClassesThat().resideInAPackage("io.graphspindle.graph..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void graphDoesNotDependOnLlmOrInboundAdapters() {
        noClasses().that().resideInAPackage("io.graphspindle.graph..")
                .should().dependOnClassesThat().resideInAnyPackage("io.graphspindle.llm..", "io.graphspindle.api..",
                        "io.graphspindle.brain..", "io.graphspindle.ingestion.pipeline..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void nothingDependsOnInboundAdapters() {
        noClasses().that().resideOutsideOfPackage("io.graphspindle.api..")
                .should().dependOnClassesThat().resideInAPackage("io.graphspindle.api..")
                .check(classes);
    }

    @Test
    void internalPackagesAreModulePrivate() {
        for (String module : new String[] {"brain", "graph", "vocabulary", "resolution", "ingestion", "knowledge",
                "conflict", "retrieval", "llm", "security", "admin"}) {
            noClasses().that().resideOutsideOfPackage("io.graphspindle." + module + "..")
                    .should().dependOnClassesThat().resideInAPackage("io.graphspindle." + module + ".internal..")
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }

    @Test
    void noPackageCycles() {
        // ingestion.ops <-> ingestion.validation is an accepted cycle inside the ingestion module (ADR-017 §13)
        slices().matching("io.graphspindle.(**)")
                .should().beFreeOfCycles()
                .ignoreDependency(inPackage(OPS), inPackage(VALIDATION))
                .ignoreDependency(inPackage(VALIDATION), inPackage(OPS))
                .check(classes);
    }

    private static DescribedPredicate<JavaClass> inPackage(String pkg) {
        return DescribedPredicate.describe(pkg, c -> c.getPackageName().equals(pkg));
    }
}
