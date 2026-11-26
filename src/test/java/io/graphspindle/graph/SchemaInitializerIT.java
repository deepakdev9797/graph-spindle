package io.graphspindle.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SchemaInitializerIT extends AbstractNeo4jIT {

    @Autowired
    SchemaInitializer initializer;

    @Test
    void createsAllConstraintsAndIndexesIdempotently() {
        initializer.initialize();
        List<Object> constraints = names("SHOW CONSTRAINTS YIELD name RETURN name");
        List<Object> indexes = names("SHOW INDEXES YIELD name, type WHERE type <> 'LOOKUP' RETURN name");
        initializer.initialize();
        assertThat(names("SHOW CONSTRAINTS YIELD name RETURN name")).isEqualTo(constraints);
        assertThat(names("SHOW INDEXES YIELD name, type WHERE type <> 'LOOKUP' RETURN name")).isEqualTo(indexes);

        assertThat(constraints).contains("brain_id_unique", "entity_id_unique", "entity_key_unique", "alias_unique",
                "claim_id_unique", "claim_active_unique", "claim_slot_unique", "ingestion_id_unique",
                "ingestion_request_unique", "source_id_unique", "source_locator_unique", "conflict_id_unique",
                "apikey_id_unique");
        assertThat(indexes).contains("entity_brain_type", "entity_brain_updated", "claim_brain_subject",
                "claim_brain_object", "claim_brain_predicate", "ingestion_brain_hash", "ingestion_brain_time",
                "conflict_brain_status", "entity_text", "claim_text", "source_text");
        var fulltext = query("SHOW FULLTEXT INDEXES YIELD name, options RETURN name, options", Map.of());
        assertThat(fulltext).hasSize(3).allSatisfy(r -> assertThat(r.get("options").toString())
                .contains("standard-no-stop-words"));
    }

    private List<Object> names(String cypher) {
        return query(cypher + " ORDER BY name", Map.of()).stream().map(r -> r.get("name")).toList();
    }
}
