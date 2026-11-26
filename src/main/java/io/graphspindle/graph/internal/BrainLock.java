package io.graphspindle.graph.internal;

import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;

/**
 * The first step of every brain-scoped write transaction: take the Brain node's write lock and
 * require the brain to be ACTIVE. Brain deletion marks the brain DELETING under the same lock, so a
 * write either commits before the deletion starts (and is then deleted with the brain) or sees
 * DELETING and fails with {@code CONFLICT_STATE}; nothing can be written into a brain that is being
 * deleted (QA M4 F2, ADR-022). Lock order is always Brain first, then other nodes.
 */
public final class BrainLock {

    private BrainLock() {
    }

    public static void lockActive(ScopedTx tx) {
        var rows = tx.run(BrainStatements.LOCK).list();
        if (rows.isEmpty()) {
            throw GraphSpindleException.brainNotFound(tx.brainId());
        }
        if (!"ACTIVE".equals(rows.get(0).get("b").get("status").asString())) {
            throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "brain is being deleted");
        }
    }
}
