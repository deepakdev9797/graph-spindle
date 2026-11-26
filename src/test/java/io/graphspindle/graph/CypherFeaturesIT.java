package io.graphspindle.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.graphspindle.graph.internal.GraphTransactions;
import io.graphspindle.support.AbstractNeo4jIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.exceptions.Neo4jException;

/**
 * Empirical checks of the SCHEMA §12 VERIFY items against neo4j-harness 2026.10.2 (ADR-020).
 * Uses raw Cypher on purpose: it documents the database behaviour the graph module relies on.
 */
class CypherFeaturesIT extends AbstractNeo4jIT {

    @Test
    void compositeUniquenessIgnoresNodesMissingAProperty() {
        // two claims of one brain without activeKey/slotKey: allowed
        write("CREATE (:Claim {brainId: 'b1', id: 'c1'}), (:Claim {brainId: 'b1', id: 'c2'})", Map.of());
        // same activeKey in two brains: allowed
        write("CREATE (:Claim {brainId: 'b1', id: 'c3', activeKey: 'k'}), (:Claim {brainId: 'b2', id: 'c4', activeKey: 'k'})",
                Map.of());
        // same activeKey twice in one brain: rejected
        assertThatThrownBy(() -> write("CREATE (:Claim {brainId: 'b1', id: 'c5', activeKey: 'k'})", Map.of()))
                .isInstanceOf(Neo4jException.class)
                .satisfies(e -> assertThat(GraphTransactions.isConstraintViolation(e)).isTrue());
        // removing the key frees the slot
        write("MATCH (c:Claim {brainId: 'b1', id: 'c3'}) REMOVE c.activeKey", Map.of());
        write("CREATE (:Claim {brainId: 'b1', id: 'c5', activeKey: 'k'})", Map.of());
        assertThat(count("MATCH (c:Claim) RETURN count(c)", Map.of())).isEqualTo(5);
    }

    @Test
    void dynamicLabelsAndRelationshipTypesWork() {
        write("CREATE (a:Entity {brainId: 'b1', id: 'a'}) WITH a SET a:$($label)", Map.of("label", "Project"));
        write("CREATE (:Entity {brainId: 'b1', id: 'b'})", Map.of());
        write("MATCH (a:Entity {brainId: 'b1', id: 'a'}), (b:Entity {brainId: 'b1', id: 'b'})"
                + " MERGE (a)-[r:$($type) {brainId: 'b1', claimId: 'c1'}]->(b)", Map.of("type", "USES"));
        write("MATCH (a:Entity {brainId: 'b1', id: 'a'}), (b:Entity {brainId: 'b1', id: 'b'})"
                + " MERGE (a)-[r:$($type) {brainId: 'b1', claimId: 'c1'}]->(b)", Map.of("type", "USES"));
        assertThat(count("MATCH (n:Project {id: 'a'}) RETURN count(n)", Map.of())).isEqualTo(1);
        assertThat(count("MATCH (:Entity {id: 'a'})-[r:USES]->(:Entity {id: 'b'}) RETURN count(r)", Map.of()))
                .isEqualTo(1);
        // a parameter is a single token: injection attempts become an (odd) label name, never Cypher
        write("CREATE (a:Entity {brainId: 'b1', id: 'x'}) WITH a SET a:$($label)",
                Map.of("label", "X` DETACH DELETE a //"));
        assertThat(count("MATCH (n:Entity {id: 'x'}) RETURN count(n)", Map.of())).isEqualTo(1);
    }

    @Test
    void scopedSubqueryAndMapPropertyRemovalWork() {
        write("CREATE (:Entity {brainId: 'b1', id: 'a', attr_status: 'ACTIVE'})", Map.of());
        write("MATCH (n:Entity {brainId: 'b1', id: 'a'}) CALL (n) { SET n += $props }",
                Map.of("props", java.util.Collections.singletonMap("attr_status", null)));
        assertThat(query("MATCH (n:Entity {id: 'a'}) RETURN keys(n) AS k", Map.of()).get(0).get("k"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).doesNotContain("attr_status");
    }

    @Test
    void fulltextQueryNodesWithOptionMapAndNoStopWords() {
        write("CREATE (:Entity {brainId: 'b1', id: 'w', name: 'The Who', status: 'ACTIVE'}),"
                + " (:Entity {brainId: 'b2', id: 'w2', name: 'The Who', status: 'ACTIVE'}),"
                + " (:Entity {brainId: 'b1', id: 'g', name: 'GraphSpindle', description: 'knowledge graph engine', status: 'ACTIVE'})",
                Map.of());
        List<Map<String, Object>> rows = query("CALL db.index.fulltext.queryNodes('entity_text', $q, {limit: $limit})"
                + " YIELD node, score WHERE node.brainId = $brainId RETURN node.id AS id, score",
                Map.of("q", "the", "limit", 10, "brainId", "b1"));
        assertThat(rows).extracting(r -> r.get("id")).containsExactly("w");
        rows = query("CALL db.index.fulltext.queryNodes('entity_text', $q, {limit: 10}) YIELD node, score"
                + " WHERE node.brainId = 'b1' RETURN node.id AS id", Map.of("q", "graphspindle"));
        assertThat(rows).extracting(r -> r.get("id")).containsExactly("g");
        rows = query("CALL db.index.fulltext.queryNodes('entity_text', $q, {limit: 10}) YIELD node, score"
                + " WHERE node.brainId = 'b1' RETURN node.id AS id", Map.of("q", "knowledge"));
        assertThat(rows).extracting(r -> r.get("id")).containsExactly("g");
    }
}
