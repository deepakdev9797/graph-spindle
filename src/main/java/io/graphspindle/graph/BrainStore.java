package io.graphspindle.graph;

import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Ids;
import io.graphspindle.graph.internal.BrainStatements;
import io.graphspindle.graph.internal.GraphTransactions;
import io.graphspindle.graph.internal.P;
import java.util.List;
import java.util.Optional;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.Neo4jException;
import org.springframework.stereotype.Component;

/** Persistence of Brain nodes (and the self entity created with each brain). */
@Component
public class BrainStore {

    private final GraphTransactions tx;

    public BrainStore(GraphTransactions tx) {
        this.tx = tx;
    }

    /**
     * Creates the brain and its self {@code Person} entity ({@code person:@self}) in one transaction.
     *
     * @throws GraphSpindleException {@code ALREADY_EXISTS} when the id is taken
     */
    public BrainRecord create(BrainScope scope, String name, String description, String configJson, String selfName) {
        String selfId = Ids.newId();
        try {
            return tx.write(scope, t -> toRecord(t.run(BrainStatements.CREATE, P.of("name", name, "description",
                    description, "configJson", configJson, "selfId", selfId, "selfName", selfName)).single()));
        } catch (Neo4jException e) {
            if (GraphTransactions.isConstraintViolation(e)) {
                throw new GraphSpindleException(ErrorCode.ALREADY_EXISTS, "brain already exists: " + scope.brainId(),
                        java.util.Map.of(), e);
            }
            throw e;
        }
    }

    public Optional<BrainRecord> find(BrainScope scope) {
        return tx.read(scope, t -> t.run(BrainStatements.FIND).list().stream().findFirst().map(BrainStore::toRecord));
    }

    public List<BrainRecord> listAll() {
        return tx.readUnscoped(t -> t.run(BrainStatements.LIST_ALL).list(BrainStore::toRecord));
    }

    /**
     * Updates name, description and/or config. Every successful update increments {@code configVersion}.
     *
     * @param expectedVersion the {@code If-Match} version, or null to skip the check
     * @param setDescription whether {@code description} (possibly null) replaces the current one
     * @param configJson new serialized config, or null to keep it
     */
    public BrainRecord update(BrainScope scope, Long expectedVersion, String name, boolean setDescription,
            String description, String configJson) {
        return tx.write(scope, t -> {
            List<Record> rows = t.run(BrainStatements.LOCK).list();
            if (rows.isEmpty()) {
                throw GraphSpindleException.brainNotFound(scope.brainId());
            }
            BrainRecord current = toRecord(rows.get(0));
            if (!current.active()) {
                throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "brain is being deleted");
            }
            if (expectedVersion != null && expectedVersion != current.configVersion()) {
                throw new GraphSpindleException(ErrorCode.PRECONDITION_FAILED, "configVersion does not match If-Match",
                        java.util.Map.of("configVersion", current.configVersion()), null);
            }
            return toRecord(t.run(BrainStatements.UPDATE, P.of("name", name, "setDescription", setDescription,
                    "description", description, "configJson", configJson)).single());
        });
    }

    /**
     * Sets the brain to DELETING, then deletes all of its nodes in batches and the Brain node last.
     * MARK_DELETING waits for the Brain lock held by any in-flight write ({@code BrainLock}), and no
     * write can start afterwards, so the re-check loop normally ends after the first pass.
     */
    public void delete(BrainScope scope) {
        boolean found = tx.write(scope, t -> !t.run(BrainStatements.MARK_DELETING).list().isEmpty());
        if (!found) {
            throw GraphSpindleException.brainNotFound(scope.brainId());
        }
        for (int pass = 0; pass < MAX_DELETE_PASSES; pass++) {
            tx.autoCommit(scope, BrainStatements.DELETE_ALL);
            boolean deleted = tx.write(scope, t -> !t.run(BrainStatements.DELETE_BRAIN_IF_EMPTY).list().isEmpty());
            if (deleted) {
                return;
            }
        }
        throw new GraphSpindleException(ErrorCode.INTERNAL, "brain deletion did not converge; retry the delete",
                java.util.Map.of(), null);
    }

    static final int MAX_DELETE_PASSES = 5;

    /**
     * Usage counts for the vocabulary-removal guard (SCHEMA §11): ACTIVE entities per type and
     * ACTIVE or DISPUTED claims per predicate name. Only names in use are returned.
     */
    public java.util.Map<String, Long> usage(BrainScope scope, java.util.Collection<String> types,
            java.util.Collection<String> predicates) {
        return tx.read(scope, t -> {
            java.util.Map<String, Long> used = new java.util.TreeMap<>();
            if (!types.isEmpty()) {
                t.run(BrainStatements.TYPES_IN_USE, P.of("types", List.copyOf(types))).list()
                        .forEach(r -> used.put(r.get("name").asString(), r.get("n").asLong()));
            }
            if (!predicates.isEmpty()) {
                t.run(BrainStatements.PREDICATES_IN_USE, P.of("predicates", List.copyOf(predicates))).list()
                        .forEach(r -> used.merge(r.get("name").asString(), r.get("n").asLong(), Long::sum));
            }
            return used;
        });
    }

    private static BrainRecord toRecord(Record r) {
        Value b = r.get("b");
        return new BrainRecord(b.get("id").asString(), b.get("name").asString(), b.get("description").asString(null),
                b.get("configJson").asString(), b.get("configVersion").asLong(), b.get("selfEntityId").asString(),
                b.get("status").asString(), b.get("createdAt").asZonedDateTime().toInstant(),
                b.get("updatedAt").asZonedDateTime().toInstant());
    }
}
