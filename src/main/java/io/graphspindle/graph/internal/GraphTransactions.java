package io.graphspindle.graph.internal;

import io.graphspindle.common.BrainScope;
import java.util.Map;
import java.util.function.Function;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.exceptions.Neo4jException;
import org.springframework.stereotype.Component;

/**
 * Driver-managed transactions (ADR-018). Callbacks may be re-executed by the driver on transient
 * errors, so they must not leak state outside their return value.
 */
@Component
public class GraphTransactions {

    public static final String CONSTRAINT_VIOLATION = "Neo.ClientError.Schema.ConstraintValidationFailed";

    private final Driver driver;

    public GraphTransactions(Driver driver) {
        this.driver = driver;
    }

    public <T> T write(BrainScope scope, Function<ScopedTx, T> work) {
        try (Session session = driver.session()) {
            return session.executeWrite(tx -> work.apply(new ScopedTx(tx, scope)));
        }
    }

    public <T> T read(BrainScope scope, Function<ScopedTx, T> work) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> work.apply(new ScopedTx(tx, scope)));
        }
    }

    /** Auto-commit statement (needed for {@code CALL ... IN TRANSACTIONS}); must reference {@code $brainId}. */
    public void autoCommit(BrainScope scope, String cypher) {
        if (!cypher.contains("$brainId")) {
            throw new IllegalStateException("unscoped Cypher statement refused");
        }
        try (Session session = driver.session()) {
            session.run(cypher, Map.of("brainId", scope.brainId())).consume();
        }
    }

    /** Unscoped access, only for schema management and the allowlisted brain listing. */
    public <T> T readUnscoped(Function<TransactionContext, T> work) {
        try (Session session = driver.session()) {
            return session.executeRead(work::apply);
        }
    }

    /** Runs a schema statement in its own auto-commit transaction. */
    public void schema(String cypher) {
        try (Session session = driver.session()) {
            session.run(cypher).consume();
        }
    }

    public static boolean isConstraintViolation(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof Neo4jException n && CONSTRAINT_VIOLATION.equals(n.code())) {
                return true;
            }
        }
        return false;
    }
}
