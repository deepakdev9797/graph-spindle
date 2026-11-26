package io.graphspindle.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.common.BrainScope;
import io.graphspindle.graph.EntityView;
import io.graphspindle.graph.GraphReader;
import io.graphspindle.ingestion.pipeline.CandidateStage;
import io.graphspindle.ingestion.pipeline.Extraction;
import io.graphspindle.brain.BrainService;
import io.graphspindle.support.AbstractIngestionIT;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * M7 acceptance: a corpus of name variants (case, spacing, punctuation, "The", known aliases, one
 * typo, type synonyms) resolves to the existing entity of brain {@code dev} through the deterministic
 * candidate path alone, the scripted choice being the top-ranked candidate of the mention; at least
 * 95% must be right. Near-duplicates created by the model open DUPLICATE_CANDIDATE conflicts.
 */
class DuplicateCorpusIT extends AbstractIngestionIT {

    @Autowired
    CandidateStage candidates;
    @Autowired
    BrainService brains;
    @Autowired
    GraphReader reader;

    private final Map<String, String> ids = new LinkedHashMap<>();

    private void seed() {
        ids.put("GraphSpindle", k.entity("dev", "Project", "GraphSpindle"));
        ids.put("Neo4j", k.entity("dev", "Technology", "Neo4j", "Neo4j Graph Database"));
        ids.put("PostgreSQL", k.entity("dev", "Technology", "PostgreSQL", "Postgres"));
        ids.put("Acme Corporation", k.entity("dev", "Organization", "Acme Corporation", "Acme Corp", "ACME"));
        ids.put("Ada Lovelace", k.entity("dev", "Person", "Ada Lovelace"));
        ids.put("Kafka", k.entity("dev", "Technology", "Kafka", "Apache Kafka"));
        ids.put("Node.js", k.entity("dev", "Technology", "Node.js"));
        ids.put("C++", k.entity("dev", "Technology", "C++"));
        ids.put("C#", k.entity("dev", "Technology", "C#"));
        ids.put("C", k.entity("dev", "Technology", "C"));
        ids.put("MIT License", k.entity("dev", "Standard", "MIT License", "MIT"));
        ids.put("Q3 Planning Meeting", k.entity("dev", "Event", "Q3 Planning Meeting"));
        // distractors with overlapping words
        k.entity("dev", "Project", "GraphSpindle Docs");
        k.entity("dev", "Technology", "Neo4j Aura");
        k.entity("dev", "Person", "Ada Smith");
        k.entity("dev", "Organization", "Acme Labs");
    }

    /** variant, mention type, expected entity name. */
    static final String[][] CORPUS = {
        {"graphspindle", "Project", "GraphSpindle"}, {"GRAPHSPINDLE", "Project", "GraphSpindle"},
        {"Graph Spindle", "Project", "GraphSpindle"}, {"graph-spindle", "Project", "GraphSpindle"},
        {"GraphSpindle.", "Project", "GraphSpindle"}, {"The GraphSpindle", "Project", "GraphSpindle"},
        {"Graph_Spindle", "Project", "GraphSpindle"}, {"\"GraphSpindle\"", "Project", "GraphSpindle"},
        {"Graphspindel", "Project", "GraphSpindle"}, {"graph spindle", "initiative", "GraphSpindle"},
        {"neo4j", "Technology", "Neo4j"}, {"NEO4J", "Technology", "Neo4j"}, {"Neo 4j", "Technology", "Neo4j"},
        {"Neo4j Graph Database", "Technology", "Neo4j"}, {"neo4j graph database", "tool", "Neo4j"},
        {"postgres", "Technology", "PostgreSQL"}, {"Postgres", "Technology", "PostgreSQL"},
        {"postgresql", "Technology", "PostgreSQL"}, {"Postgre SQL", "Technology", "PostgreSQL"},
        {"PostgreSQL!", "Technology", "PostgreSQL"},
        {"acme corp", "Organization", "Acme Corporation"}, {"Acme Corp.", "Organization", "Acme Corporation"},
        {"ACME", "company", "Acme Corporation"}, {"The Acme Corporation", "Organization", "Acme Corporation"},
        {"acme corporation", "Organization", "Acme Corporation"},
        {"ada lovelace", "Person", "Ada Lovelace"}, {"Ada  Lovelace", "Person", "Ada Lovelace"},
        {"ADA LOVELACE", "Person", "Ada Lovelace"}, {"Ada Lovelace,", "contact", "Ada Lovelace"},
        {"Apache Kafka", "Technology", "Kafka"}, {"apache kafka", "Technology", "Kafka"},
        {"kafka", "Technology", "Kafka"}, {"KAFKA", "platform", "Kafka"},
        {"nodejs", "Technology", "Node.js"}, {"Node JS", "Technology", "Node.js"}, {"node.js", "Technology", "Node.js"},
        {"NodeJS", "framework", "Node.js"},
        {"c++", "Technology", "C++"}, {"C#", "Technology", "C#"}, {"c", "language", "C"},
        {"MIT", "Standard", "MIT License"}, {"mit license", "license", "MIT License"},
        {"The MIT License", "Standard", "MIT License"},
        {"q3 planning meeting", "Event", "Q3 Planning Meeting"}, {"Q3 planning-meeting", "meeting", "Q3 Planning Meeting"},
    };

    @Test
    void nameVariantsResolveToTheExistingEntity() {
        seed();
        BrainScope dev = BrainScope.of("dev");
        int right = 0;
        List<String> wrong = new ArrayList<>();
        Set<String> devIds = Set.copyOf(query("MATCH (n:Entity {brainId:'dev'}) RETURN n.id AS id", Map.of()).stream()
                .map(r -> (String) r.get("id")).toList());
        for (String[] v : CORPUS) {
            Extraction ex = new Extraction("{}", List.of(new Extraction.Mention(v[0], v[1], List.of())), Set.of());
            CandidateStage.Candidates c = candidates.find(dev, brains.vocabulary(dev), ex, null, "speaker");
            c.set().entities().values().forEach(e -> assertThat(devIds).contains(e.entityId()));
            String chosen = c.matches().get(0).best().map(CandidateStage.Match::entityId).orElse(null);
            if (ids.get(v[2]).equals(chosen)) {
                right++;
            } else {
                EntityView got = chosen == null ? null : reader.entity(dev, chosen).orElse(null);
                wrong.add(v[0] + " -> " + (got == null ? "nothing" : got.name()));
            }
        }
        double accuracy = (double) right / CORPUS.length;
        System.out.printf("DuplicateCorpusIT: %d/%d variants resolved (%.1f%%); wrong: %s%n", right, CORPUS.length,
                accuracy * 100, wrong);
        assertThat(CORPUS.length).isGreaterThanOrEqualTo(40);
        assertThat(accuracy).as("wrong: " + wrong).isGreaterThanOrEqualTo(0.95);
        assertOtherBrainUntouched();
    }

    @Test
    void aCreatedNearDuplicateOpensADuplicateCandidateConflict() {
        seed();
        String text = "Graph Spindle's repo is on GitHub.";
        script(text, List.of("Graph Spindle:Project", "GitHub:Organization"), """
                [{"op":"create_entity","ref":"x","type":"Project","name":"Graph Spindle"},
                 {"op":"create_entity","ref":"s","type":"Project","name":"Spindle Analytics"}]""");
        JsonNode r = ingestText("dev", text, "");
        assertThat(r.get("adjusted").toString()).contains("DUPLICATE_SUSPECTED").contains("GraphSpindle");
        List<String> open = openConflicts("dev", "DUPLICATE_CANDIDATE");
        assertThat(open).hasSize(1);
        List<Map<String, Object>> pair = query("""
                MATCH (x:Conflict {brainId:'dev', kind:'DUPLICATE_CANDIDATE'})-[i:INVOLVES]->(e:Entity)
                RETURN i.role AS role, e.name AS name ORDER BY role""", Map.of());
        assertThat(pair).extracting(m -> m.get("role") + "=" + m.get("name"))
                .containsExactly("CANDIDATE_A=Graph Spindle", "CANDIDATE_B=GraphSpindle");
        // the engine never merges by itself: both exist until a person resolves the conflict
        assertThat(count("MATCH (n:Entity {brainId:'dev', type:'Project', status:'ACTIVE'}) RETURN count(n)",
                Map.of())).isEqualTo(4);
        assertThat(resolve("dev", open.get(0), "MERGE").status()).isEqualTo(200);
        assertThat(count("MATCH (n:Entity {brainId:'dev', type:'Project', status:'ACTIVE'}) RETURN count(n)",
                Map.of())).isEqualTo(3);
        assertThat(count("MATCH (n:Entity {brainId:'dev', name:'Graph Spindle', status:'MERGED'}) RETURN count(n)",
                Map.of())).isEqualTo(1);
        assertOtherBrainUntouched();
    }

    @Test
    void aModelProposedMergeIsNotDoubledAndThresholdIsConfigurable() {
        seed();
        String text = "Graph Spindle's repo is on GitHub.";
        script(text, List.of("Graph Spindle:Project"), """
                [{"op":"create_entity","ref":"x","type":"Project","name":"Graph Spindle"},
                 {"op":"propose_merge","a":{"ref":"x"},"b":{"id":"@entity:GraphSpindle"},"reason":"spacing variant"}]""");
        JsonNode r = ingestText("dev", text, "");
        assertThat(r.get("adjusted").toString()).doesNotContain("DUPLICATE_SUSPECTED");
        assertThat(openConflicts("dev", "DUPLICATE_CANDIDATE")).hasSize(1);

        // brain "strict" only suspects exact compact matches: a one-letter variant opens nothing there
        k.brain("strict");
        assertThat(api().patch("/api/v1/brains/strict", "{\"config\":{\"duplicateSuspicionScore\":1.0}}", null)
                .status()).isEqualTo(200);
        k.entity("strict", "Person", "Jon Smith");
        String people = "John Smith joined the team.";
        script(people, List.of("John Smith:Person"), """
                [{"op":"create_entity","ref":"j","type":"Person","name":"John Smith"}]""");
        assertThat(ingestText("strict", people, "").get("adjusted").toString()).doesNotContain("DUPLICATE_SUSPECTED");
        assertThat(openConflicts("strict", "DUPLICATE_CANDIDATE")).isEmpty();
        // with the default threshold the same variant is suspected
        k.entity("dev", "Person", "Jon Smith");
        script(people, List.of("John Smith:Person"), """
                [{"op":"create_entity","ref":"j","type":"Person","name":"John Smith"}]""");
        assertThat(ingestText("dev", people, "?force=true").get("adjusted").toString()).contains("DUPLICATE_SUSPECTED");
        assertOtherBrainUntouched();
    }
}
