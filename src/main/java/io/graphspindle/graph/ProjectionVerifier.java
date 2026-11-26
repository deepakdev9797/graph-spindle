package io.graphspindle.graph;

import io.graphspindle.common.BrainScope;
import io.graphspindle.graph.internal.GraphTransactions;
import io.graphspindle.graph.internal.ProjectionStore;
import io.graphspindle.graph.internal.VerifierStatements;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Component;

/**
 * Recomputes projections from claims and reports drift (SCHEMA §9.3, I3): missing or duplicate
 * relation edges, orphan edges, wrong {@code attr_*} values, and keys on non-ACTIVE claims.
 */
@Component
public class ProjectionVerifier {

    private final GraphTransactions tx;

    public ProjectionVerifier(GraphTransactions tx) {
        this.tx = tx;
    }

    public record Drift(String kind, String entityId, String claimId, String detail) {
    }

    public List<Drift> verify(BrainScope scope) {
        return tx.read(scope, t -> {
            List<Drift> drift = new ArrayList<>();
            t.run(VerifierStatements.MISSING_EDGES).list().forEach(r -> drift.add(new Drift("EDGE_COUNT",
                    r.get("subjectId").asString(), r.get("claimId").asString(), "edges=" + r.get("n").asInt())));
            t.run(VerifierStatements.ORPHAN_EDGES).list().forEach(r -> drift.add(new Drift("ORPHAN_EDGE",
                    r.get("subjectId").asString(), r.get("claimId").asString(), r.get("type").asString())));
            t.run(VerifierStatements.KEY_DRIFT).list().forEach(r -> drift.add(new Drift("KEYS",
                    r.get("subjectId").asString(null), r.get("claimId").asString(), "keys do not match status")));

            Map<String, Map<String, List<String>>> values = new HashMap<>();
            Map<String, String> cardinality = new HashMap<>();
            t.run(VerifierStatements.ACTIVE_ATTRIBUTE_CLAIMS).list().forEach(r -> {
                String key = "attr_" + r.get("predicate").asString();
                values.computeIfAbsent(r.get("subjectId").asString(), k -> new TreeMap<>())
                        .computeIfAbsent(key, k -> new ArrayList<>()).add(r.get("value").asString());
                cardinality.putIfAbsent(r.get("subjectId").asString() + "/" + key, r.get("cardinality").asString(null));
            });
            t.run(VerifierStatements.ENTITY_ATTRIBUTES).list().forEach(r -> {
                String id = r.get("id").asString();
                Map<String, Object> actual = new TreeMap<>();
                for (Value pair : r.get("attrs").values()) {
                    actual.put(pair.get(0).asString(), pair.get(1).asObject());
                }
                Map<String, Object> expected = new TreeMap<>();
                values.getOrDefault(id, Map.of()).forEach((k, v) -> expected.put(k, ProjectionStore.expectedAttribute(
                        v.stream().sorted().toList(), cardinality.get(id + "/" + k))));
                if (!Objects.equals(actual, expected)) {
                    drift.add(new Drift("ATTRIBUTE", id, null, "expected " + expected + " but was " + actual));
                }
            });
            return drift;
        });
    }
}
