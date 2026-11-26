package io.graphspindle.graph.internal;

import java.util.List;
import java.util.stream.Stream;

/**
 * Constraints and indexes (SCHEMA §8). Schema statements are allowlisted in CypherScopingTest.
 * All are Community-edition features; behaviour verified against neo4j-harness 2026.10.2 (ADR-020).
 */
public final class SchemaStatements {

    private SchemaStatements() {
    }

    public static final List<String> CONSTRAINTS = List.of(
            "CREATE CONSTRAINT brain_id_unique IF NOT EXISTS FOR (b:Brain) REQUIRE b.id IS UNIQUE",
            "CREATE CONSTRAINT entity_id_unique IF NOT EXISTS FOR (n:Entity) REQUIRE (n.brainId, n.id) IS UNIQUE",
            "CREATE CONSTRAINT entity_key_unique IF NOT EXISTS FOR (n:Entity) REQUIRE (n.brainId, n.canonicalKey) IS UNIQUE",
            "CREATE CONSTRAINT alias_unique IF NOT EXISTS FOR (a:Alias) REQUIRE (a.brainId, a.normalized) IS UNIQUE",
            "CREATE CONSTRAINT claim_id_unique IF NOT EXISTS FOR (c:Claim) REQUIRE (c.brainId, c.id) IS UNIQUE",
            "CREATE CONSTRAINT claim_active_unique IF NOT EXISTS FOR (c:Claim) REQUIRE (c.brainId, c.activeKey) IS UNIQUE",
            "CREATE CONSTRAINT claim_slot_unique IF NOT EXISTS FOR (c:Claim) REQUIRE (c.brainId, c.slotKey) IS UNIQUE",
            "CREATE CONSTRAINT ingestion_id_unique IF NOT EXISTS FOR (i:Ingestion) REQUIRE (i.brainId, i.id) IS UNIQUE",
            "CREATE CONSTRAINT ingestion_request_unique IF NOT EXISTS FOR (i:Ingestion) REQUIRE (i.brainId, i.requestId) IS UNIQUE",
            "CREATE CONSTRAINT source_id_unique IF NOT EXISTS FOR (s:SourceRef) REQUIRE (s.brainId, s.id) IS UNIQUE",
            "CREATE CONSTRAINT source_locator_unique IF NOT EXISTS FOR (s:SourceRef) REQUIRE (s.brainId, s.kind, s.locatorNorm) IS UNIQUE",
            "CREATE CONSTRAINT conflict_id_unique IF NOT EXISTS FOR (x:Conflict) REQUIRE (x.brainId, x.id) IS UNIQUE",
            "CREATE CONSTRAINT apikey_id_unique IF NOT EXISTS FOR (k:ApiKey) REQUIRE k.id IS UNIQUE");

    public static final List<String> INDEXES = List.of(
            "CREATE INDEX entity_brain_type IF NOT EXISTS FOR (n:Entity) ON (n.brainId, n.type, n.status)",
            "CREATE INDEX entity_brain_updated IF NOT EXISTS FOR (n:Entity) ON (n.brainId, n.updatedAt)",
            "CREATE INDEX claim_brain_subject IF NOT EXISTS FOR (c:Claim) ON (c.brainId, c.subjectId, c.status)",
            "CREATE INDEX claim_brain_object IF NOT EXISTS FOR (c:Claim) ON (c.brainId, c.objectId, c.status)",
            "CREATE INDEX claim_brain_predicate IF NOT EXISTS FOR (c:Claim) ON (c.brainId, c.predicate, c.status)",
            "CREATE INDEX ingestion_brain_hash IF NOT EXISTS FOR (i:Ingestion) ON (i.brainId, i.contentHash)",
            "CREATE INDEX ingestion_brain_time IF NOT EXISTS FOR (i:Ingestion) ON (i.brainId, i.receivedAt)",
            "CREATE INDEX conflict_brain_status IF NOT EXISTS FOR (x:Conflict) ON (x.brainId, x.status)");

    /** Fulltext indexes use the no-stop-words analyzer so names such as "The Who" or "It" stay searchable. */
    public static final List<String> FULLTEXT = List.of(
            "CREATE FULLTEXT INDEX entity_text IF NOT EXISTS FOR (n:Entity) ON EACH [n.name, n.description]"
                    + " OPTIONS {indexConfig: {`fulltext.analyzer`: 'standard-no-stop-words'}}",
            "CREATE FULLTEXT INDEX claim_text IF NOT EXISTS FOR (c:Claim) ON EACH [c.statement]"
                    + " OPTIONS {indexConfig: {`fulltext.analyzer`: 'standard-no-stop-words'}}",
            "CREATE FULLTEXT INDEX source_text IF NOT EXISTS FOR (s:SourceRef) ON EACH [s.title, s.summary]"
                    + " OPTIONS {indexConfig: {`fulltext.analyzer`: 'standard-no-stop-words'}}");

    public static final List<String> ALL = Stream.of(CONSTRAINTS, INDEXES, FULLTEXT).flatMap(List::stream).toList();
}
