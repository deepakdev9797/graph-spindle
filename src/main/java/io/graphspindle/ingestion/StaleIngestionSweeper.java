package io.graphspindle.ingestion;

import io.graphspindle.common.BrainScope;
import io.graphspindle.graph.BrainRecord;
import io.graphspindle.graph.BrainStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * At startup, marks ingestions left RECEIVED by a crashed or stopped process (older than the LLM time
 * budget plus {@link IngestionService#STALE_MARGIN}) as FAILED with code {@code STALE}, so their
 * requestIds can be retried (ADR-031). A replay of a stale requestId does the same on demand.
 */
@Component
public class StaleIngestionSweeper {

    private static final Logger log = LoggerFactory.getLogger(StaleIngestionSweeper.class);

    private final BrainStore brains;
    private final IngestionService ingestions;

    public StaleIngestionSweeper(BrainStore brains, IngestionService ingestions) {
        this.brains = brains;
        this.ingestions = ingestions;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sweep() {
        int total = 0;
        try {
            for (BrainRecord b : brains.listAll()) {
                if (b.active()) {
                    total += ingestions.markStale(BrainScope.of(b.id()), null);
                }
            }
        } catch (RuntimeException e) {
            log.warn("stale ingestion sweep failed ({}); stale requestIds are released on replay instead",
                    e.getClass().getSimpleName());
            return;
        }
        if (total > 0) {
            log.info("marked {} stale RECEIVED ingestion(s) FAILED", total);
        }
    }
}
