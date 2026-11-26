package io.graphspindle.graph;

import io.graphspindle.graph.internal.GraphTransactions;
import io.graphspindle.graph.internal.SchemaStatements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Creates all constraints and indexes (SCHEMA §8) idempotently at startup. Disable with
 * {@code graphspindle.graph.initialize-schema=false} (only for tests without a database).
 */
@Component
@ConditionalOnProperty(name = "graphspindle.graph.initialize-schema", havingValue = "true", matchIfMissing = true)
public class SchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final GraphTransactions tx;

    public SchemaInitializer(GraphTransactions tx) {
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        initialize();
    }

    public void initialize() {
        for (String statement : SchemaStatements.ALL) {
            tx.schema(statement);
        }
        log.info("Neo4j schema initialized ({} statements)", SchemaStatements.ALL.size());
    }
}
