package io.graphspindle.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ClaimKind;
import io.graphspindle.common.ConflictPolicy;
import io.graphspindle.common.Origin;
import io.graphspindle.common.Polarity;
import io.graphspindle.graph.ApplyResult.Outcome;
import io.graphspindle.ingestion.ops.ProposalParser;
import io.graphspindle.ingestion.validation.CandidateSet;
import io.graphspindle.ingestion.validation.ClaimCandidate;
import io.graphspindle.ingestion.validation.EntityCandidate;
import io.graphspindle.ingestion.validation.OperationValidator;
import io.graphspindle.ingestion.validation.ValidatedPlan;
import io.graphspindle.ingestion.validation.ValidationContext;
import io.graphspindle.vocabulary.ValueType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The SCHEMA §10 worked examples, parsed and validated by the M3 code and applied by GraphStore
 * (no LLM): the graph after each message matches the documented effects.
 */
class PlanApplyIT extends AbstractGraphIT {

    static final String MSG1 =
            "I am building GraphSpindle in Java. It uses Neo4j and Spring AI. I decided to use Apache 2.0.";
    static final String MSG2 = "GraphSpindle now exposes an MCP server too. Alice Chen from Acme is helping me with "
            + "the Neo4j store. Design notes are in docs/ARCHITECTURE.md.";
    static final String MSG3 = "Change of plan: we're switching GraphSpindle from Apache 2.0 to the MIT license.";
    static final String CORRECTION = """
            {"operations":[
              {"op":"create_entity","ref":"s1","type":"Standard","name":"MIT License","aliases":["MIT"]},
              {"op":"assert_claim","subject":{"ref":"s1"},"predicate":"category","value":"LICENSE","statement":"MIT is a license.","evidence":"the MIT license","confidence":0.9},
              {"op":"create_entity","ref":"d2","type":"Decision","name":"Use MIT License for GraphSpindle"},
              {"op":"assert_claim","subject":{"id":"P0"},"predicate":"DECIDED","object":{"ref":"d2"},"statement":"The speaker decided to switch to MIT.","evidence":"we're switching GraphSpindle from Apache 2.0 to the MIT license","confidence":0.9},
              {"op":"assert_claim","subject":{"ref":"d2"},"predicate":"CHOOSES","object":{"ref":"s1"},"statement":"The decision chooses MIT.","evidence":"to the MIT license","confidence":0.9},
              {"op":"assert_claim","subject":{"ref":"d2"},"predicate":"APPLIES_TO","object":{"id":"e1"},"statement":"Applies to GraphSpindle.","evidence":"switching GraphSpindle","confidence":0.9},
              {"op":"assert_claim","subject":{"ref":"d2"},"predicate":"REPLACES","object":{"id":"e6"},"statement":"The MIT decision replaces the Apache 2.0 decision.","evidence":"Change of plan","confidence":0.9},
              {"op":"supersede_claim","claimId":"c13","explicitCorrection":true,"reason":"License changed from Apache 2.0 to MIT",
               "replacement":{"subject":{"id":"e1"},"predicate":"LICENSED_UNDER","object":{"ref":"s1"},"statement":"GraphSpindle is licensed under MIT.","evidence":"switching GraphSpindle from Apache 2.0 to the MIT license","confidence":0.9}}
            ]}
            """;

    final ProposalParser parser = new ProposalParser();
    final OperationValidator validator = new OperationValidator();
    BrainScope dev;
    BrainScope other;

    @BeforeEach
    void brains() {
        dev = brain("dev");
        other = brain("other");
        apply(other, create(0, "p", "Project", "GraphSpindle"));
    }

    ApplyResult ingest(String content, String proposal, CandidateSet candidates, List<SourceInput> sources) {
        ValidatedPlan plan = validator.validate(parser.parse(proposal), ValidationContext.of(dev.brainId(), CORE,
                candidates, content, sources.stream().map(SourceInput::summary).toList()));
        assertThat(plan.rejected()).isEmpty();
        IngestionRecord i = ingestion(dev, "dev-key");
        return graph.apply(dev, new ApplyRequest(i.id(), "dev-key", Origin.LLM, sources, plan, CORE,
                ConflictPolicy.EXPLICIT_CORRECTIONS_SUPERSEDE, true,
                new IngestionCompletion(plan.summary(), 19, 0, "[]", "fake", "fake-model", "reconcile-v1")));
    }

    EntityCandidate candidate(String handle, String key) {
        EntityView e = reader.entityByKey(dev, key).orElseThrow();
        return new EntityCandidate(handle, e.id(), e.type(), e.name(), e.normalizedName(), e.canonicalKey(),
                Set.copyOf(reader.aliasesOf(dev, e.id())), e.descriptionLocked(), Set.of(e.type()));
    }

    EntityCandidate self() {
        return new EntityCandidate("P0", selfId(dev), "Person", "Me", "@self", "person:@self", Set.of(), false,
                Set.of());
    }

    ClaimCandidate claimCandidate(String handle, String subjectId, String predicate) {
        ClaimView c = reader.claimsOfSubject(dev, subjectId).stream()
                .filter(x -> x.predicate().equals(predicate) && x.active()).findFirst().orElseThrow();
        return new ClaimCandidate(handle, c.id(), c.subjectId(), c.predicate(), ClaimKind.valueOf(c.kind()),
                c.objectId(), c.valueType() == null ? null : ValueType.valueOf(c.valueType()), c.valueNorm(),
                Polarity.valueOf(c.polarity()), Origin.valueOf(c.origin()));
    }

    long devCount(String cypher) {
        return count(cypher, Map.of("b", "dev"));
    }

    void message1() {
        ApplyResult r = ingest(MSG1, resource("readme-example.json"), CandidateSet.builder().entity(self()).build(),
                List.of());
        assertThat(r.status()).isEqualTo("APPLIED");
        assertThat(r.opsApplied()).isEqualTo(19);
    }

    @Test
    void readmeExampleProducesTheDocumentedGraph() {
        message1();
        assertThat(devCount("MATCH (n:Entity {brainId: $b}) RETURN count(n)")).isEqualTo(7);
        assertThat(devCount("MATCH (c:Claim {brainId: $b, status: 'ACTIVE'}) RETURN count(c)")).isEqualTo(13);
        assertThat(devCount("MATCH (c:Claim {brainId: $b}) RETURN count(c)")).isEqualTo(13);
        assertThat(devCount("MATCH (a:Alias {brainId: $b}) RETURN count(a)")).isEqualTo(8);
        var edges = query("MATCH (s:Entity {brainId: $b})-[r]->(o:Entity {brainId: $b}) WHERE r.claimId IS NOT NULL"
                + " RETURN s.name + ' ' + type(r) + ' ' + o.name AS e ORDER BY e", Map.of("b", "dev"));
        assertThat(edges).extracting(m -> m.get("e")).containsExactlyInAnyOrder(
                "Me WORKS_ON GraphSpindle", "GraphSpindle WRITTEN_IN Java", "GraphSpindle USES Neo4j",
                "GraphSpindle USES Spring AI", "GraphSpindle LICENSED_UNDER Apache License 2.0",
                "Me DECIDED Use Apache License 2.0 for GraphSpindle",
                "Use Apache License 2.0 for GraphSpindle APPLIES_TO GraphSpindle",
                "Use Apache License 2.0 for GraphSpindle CHOOSES Apache License 2.0");
        assertThat(reader.entityByKey(dev, "technology:java").orElseThrow().attributes())
                .containsEntry("category", "LANGUAGE");
        assertThat(reader.entityByKey(dev, "decision:use apache license 2.0 for graphspindle").orElseThrow()
                .attributes()).containsEntry("status", "ACCEPTED");
        assertThat(reader.entitiesByAlias(dev, "apache-2.0")).extracting(EntityView::name)
                .containsExactly("Apache License 2.0");
        var ingestion = query("MATCH (i:Ingestion {brainId: $b}) RETURN i.status AS status, i.opsApplied AS applied,"
                + " i.rawContent AS raw, i.llmModel AS model", Map.of("b", "dev")).get(0);
        assertThat(ingestion).containsEntry("status", "APPLIED").containsEntry("applied", 19L)
                .containsEntry("model", "fake-model");
        assertThat(ingestion.get("raw")).isNull();
        assertThat(devCount("MATCH (c:Claim {brainId: $b})-[:EVIDENCED_BY]->(:Ingestion) RETURN count(c)"))
                .isEqualTo(13);
        assertThat(devCount("MATCH (n:Entity {brainId: $b})-[:MENTIONED_IN]->(:Ingestion) RETURN count(n)"))
                .isEqualTo(7);
        assertThat(verifier.verify(dev)).isEmpty();
        // the other brain keeps exactly its own nodes: Brain, self, GraphSpindle, its alias, its ingestion
        assertThat(count("MATCH (n {brainId: 'other'}) RETURN count(n)", Map.of())).isEqualTo(5);
    }

    @Test
    void updateMessageReinforcesAndLinksTheSourceWithoutDuplicates() {
        message1();
        String gs = reader.entityByKey(dev, "project:graphspindle").orElseThrow().id();
        String neo = reader.entityByKey(dev, "technology:neo4j").orElseThrow().id();
        CandidateSet candidates = CandidateSet.builder().entity(self())
                .entity(candidate("e1", "project:graphspindle")).entity(candidate("e3", "technology:neo4j"))
                .claim(claimCandidate("c4", gs, "USES")).build();
        var source = new SourceInput("FILE", "docs/ARCHITECTURE.md", null, "Architecture of GraphSpindle", null, null);
        ApplyResult r = ingest(MSG2, resource("update-example.json"), candidates, List.of(source));
        assertThat(r.outcomes(Outcome.CLAIM_REINFORCED)).hasSize(1);
        assertThat(devCount("MATCH (n:Entity {brainId: $b, canonicalKey: 'project:graphspindle'}) RETURN count(n)"))
                .isEqualTo(1);
        assertThat(reader.entity(dev, gs).orElseThrow().mentionCount()).isEqualTo(2);
        ClaimView uses = reader.claimsOfSubject(dev, gs).stream()
                .filter(c -> c.predicate().equals("USES") && neo.equals(c.objectId())).findFirst().orElseThrow();
        assertThat(uses.evidenceCount()).isEqualTo(2);
        assertThat(count("MATCH (:Entity {id: $g})-[r:USES]->(:Entity {id: $n}) RETURN count(r)",
                Map.of("g", gs, "n", neo))).isEqualTo(1);
        var src = query("MATCH (s:SourceRef {brainId: $b})-[:DESCRIBES {claimless: true}]->(e:Entity)"
                + " MATCH (s)-[:SUBMITTED_IN]->(i:Ingestion) RETURN s.locator AS locator, s.locatorNorm AS norm,"
                + " e.id AS entity, s.summary AS summary", Map.of("b", "dev"));
        assertThat(src).singleElement().satisfies(m -> assertThat(m).containsEntry("locator", "docs/ARCHITECTURE.md")
                .containsEntry("entity", gs).containsEntry("summary", "Architecture of GraphSpindle"));
        assertThat(devCount("MATCH (n:Entity {brainId: $b}) RETURN count(n)")).isEqualTo(10);
        assertThat(verifier.verify(dev)).isEmpty();
    }

    @Test
    void explicitCorrectionSupersedesAndTheConsequenceRuleSupersedesTheOldDecision() {
        message1();
        String gs = reader.entityByKey(dev, "project:graphspindle").orElseThrow().id();
        String oldDecision = reader.entityByKey(dev, "decision:use apache license 2.0 for graphspindle").orElseThrow()
                .id();
        CandidateSet candidates = CandidateSet.builder().entity(self())
                .entity(candidate("e1", "project:graphspindle")).entity(candidate("e5", "standard:apache license 2.0"))
                .entity(candidate("e6", "decision:use apache license 2.0 for graphspindle"))
                .claim(claimCandidate("c13", gs, "LICENSED_UNDER")).claim(claimCandidate("c11", oldDecision, "CHOOSES"))
                .claim(claimCandidate("c12", oldDecision, "status")).build();
        String c13 = candidates.claim("c13").orElseThrow().claimId();
        String c12 = candidates.claim("c12").orElseThrow().claimId();
        ApplyResult r = ingest(MSG3, CORRECTION, candidates, List.of());

        assertThat(reader.claim(dev, c13).orElseThrow().status()).isEqualTo("SUPERSEDED");
        ClaimView mit = reader.claimsOfSubject(dev, gs).stream()
                .filter(c -> c.predicate().equals("LICENSED_UNDER") && c.active()).findFirst().orElseThrow();
        assertThat(reader.entity(dev, mit.objectId()).orElseThrow().name()).isEqualTo("MIT License");
        assertThat(count("MATCH (:Claim {id: $n})-[:SUPERSEDES]->(:Claim {id: $o}) RETURN count(*)",
                Map.of("n", mit.id(), "o", c13))).isEqualTo(1);
        assertThat(query("MATCH (:Entity {id: $g})-[:LICENSED_UNDER]->(o) RETURN o.name AS n", Map.of("g", gs)))
                .extracting(m -> m.get("n")).containsExactly("MIT License");

        assertThat(r.outcomes(Outcome.CONSEQUENCE_APPLIED)).hasSize(1);
        assertThat(reader.claim(dev, c12).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(reader.entity(dev, oldDecision).orElseThrow().attributes()).containsEntry("status", "SUPERSEDED");
        ClaimView system = reader.claimsOfSubject(dev, oldDecision).stream()
                .filter(c -> c.predicate().equals("status") && c.active()).findFirst().orElseThrow();
        assertThat(system.origin()).isEqualTo("SYSTEM");
        assertThat(system.valueNorm()).isEqualTo("SUPERSEDED");
        // history: both license claims, in order
        assertThat(query("MATCH (c:Claim {brainId: $b, subjectId: $g, predicate: 'LICENSED_UNDER'}) RETURN c.status AS s"
                + " ORDER BY c.recordedAt, c.id", Map.of("b", "dev", "g", gs))).extracting(m -> m.get("s"))
                .containsExactly("SUPERSEDED", "ACTIVE");
        assertThat(verifier.verify(dev)).isEmpty();
    }

    @Test
    void manualKnowledgeIsProtected() {
        message1();
        String gs = reader.entityByKey(dev, "project:graphspindle").orElseThrow().id();
        // simulate a MANUAL claim and a locked description (manual APIs arrive in M5)
        write("MATCH (c:Claim {brainId: 'dev', subjectId: $g, predicate: 'LICENSED_UNDER'}) SET c.origin = 'MANUAL'",
                Map.of("g", gs));
        write("MATCH (n:Entity {brainId: 'dev', id: $g}) SET n.descriptionLocked = true, n.description = 'mine'",
                Map.of("g", gs));
        CandidateSet candidates = CandidateSet.builder().entity(self())
                .entity(candidate("e1", "project:graphspindle")).entity(candidate("e5", "standard:apache license 2.0"))
                .entity(candidate("e6", "decision:use apache license 2.0 for graphspindle"))
                .claim(claimCandidate("c13", gs, "LICENSED_UNDER")).build();
        String c13 = candidates.claim("c13").orElseThrow().claimId();
        ValidatedPlan plan = validator.validate(parser.parse("""
                {"operations":[
                  {"op":"create_entity","ref":"s1","type":"Standard","name":"MIT License"},
                  {"op":"update_entity","target":{"id":"e1"},"description":"overwritten by the model"},
                  {"op":"supersede_claim","claimId":"c13","explicitCorrection":true,"reason":"switch",
                   "replacement":{"subject":{"id":"e1"},"predicate":"LICENSED_UNDER","object":{"ref":"s1"},
                     "statement":"GraphSpindle is MIT.","evidence":"to the MIT license","confidence":0.9}}]}
                """), ValidationContext.of("dev", CORE, candidates, MSG3, List.of()));
        IngestionRecord i = ingestion(dev, "dev-key");
        ApplyResult r = graph.apply(dev, new ApplyRequest(i.id(), "dev-key", Origin.LLM, List.of(), plan, CORE,
                ConflictPolicy.EXPLICIT_CORRECTIONS_SUPERSEDE, true, null));

        assertThat(r.outcomes(Outcome.PROTECTED_OVERRIDE)).hasSize(2);
        assertThat(reader.claim(dev, c13).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(reader.entity(dev, gs).orElseThrow().description()).isEqualTo("mine");
        assertThat(devCount("MATCH (x:Conflict {brainId: $b, kind: 'PROTECTED_OVERRIDE', status: 'OPEN'})"
                + " RETURN count(x)")).isEqualTo(2);
        assertThat(devCount("MATCH (c:Claim {brainId: $b, status: 'DISPUTED', predicate: 'LICENSED_UNDER'})"
                + " RETURN count(c)")).isEqualTo(1);
        assertThat(verifier.verify(dev)).isEmpty();
    }

    @Test
    void proposeMergeOpensOneDuplicateCandidateConflict() {
        message1();
        CandidateSet candidates = CandidateSet.builder().entity(self())
                .entity(candidate("e1", "project:graphspindle")).build();
        String content = "Graph Spindle's repo is on GitHub.";
        String proposal = """
                {"operations":[{"op":"create_entity","ref":"x","type":"Project","name":"Graph Spindle"},
                  {"op":"propose_merge","a":{"ref":"x"},"b":{"id":"e1"},"reason":"spacing variant"}]}
                """;
        ingest(content, proposal, candidates, List.of());
        ingest(content, proposal, CandidateSet.builder().entity(self()).entity(candidate("e1", "project:graphspindle"))
                .entity(candidate("e2", "project:graph spindle")).build(), List.of());
        assertThat(devCount("MATCH (x:Conflict {brainId: $b, kind: 'DUPLICATE_CANDIDATE'})-[i:INVOLVES]->(:Entity)"
                + " RETURN count(i)")).isEqualTo(2);
        assertThat(devCount("MATCH (x:Conflict {brainId: $b}) RETURN count(x)")).isEqualTo(1);
    }

    static String resource(String name) {
        try (InputStream in = PlanApplyIT.class.getResourceAsStream("/proposals/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
