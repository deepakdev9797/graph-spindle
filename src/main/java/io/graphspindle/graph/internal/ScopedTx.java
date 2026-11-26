package io.graphspindle.graph.internal;

import io.graphspindle.common.BrainScope;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.neo4j.driver.Result;
import org.neo4j.driver.TransactionContext;

/**
 * A driver transaction bound to exactly one brain. {@link #run} refuses statements that do not
 * reference {@code $brainId} and always binds {@code brainId} itself, so a caller can neither forget
 * the scope nor pass another brain's id (ADR-004 defence in depth).
 */
public final class ScopedTx {

    private final TransactionContext tx;
    private final BrainScope scope;

    ScopedTx(TransactionContext tx, BrainScope scope) {
        this.tx = Objects.requireNonNull(tx);
        this.scope = Objects.requireNonNull(scope);
    }

    public BrainScope scope() {
        return scope;
    }

    public String brainId() {
        return scope.brainId();
    }

    public Result run(String cypher, Map<String, Object> params) {
        if (!cypher.contains("$brainId")) {
            throw new IllegalStateException("unscoped Cypher statement refused");
        }
        if (params.containsKey("brainId")) {
            throw new IllegalArgumentException("brainId is bound by the scope, not by callers");
        }
        Map<String, Object> p = new HashMap<>(params);
        p.put("brainId", scope.brainId());
        return tx.run(cypher, p);
    }

    public Result run(String cypher) {
        return run(cypher, Map.of());
    }
}
