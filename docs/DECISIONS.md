# Architecture decision records

Short ADRs. Status values are Accepted, Proposed and Superseded. Each new decision is appended here. Old entries are never rewritten: an ADR that is replaced gets status "Superseded by ADR-n".

---

## ADR-001: Technology stack and versions
**Status:** Accepted (2026-11-26)

**Decision**
- Java 21, Spring Boot 4.2.0, Spring AI 2.1.0 (BOM), Spring MVC REST, springdoc-openapi 3.x (3.2.1 in the pom).
- Spring AI MCP server starter (webmvc), Neo4j through Spring Data Neo4j's `Neo4jClient` and driver, JUnit 5, Maven wrapper.

**Rationale:** these are current supported lines compatible with each other (Boot 4 / Spring Framework 7 / Spring AI 2). They were already settled by the project owner.

**Consequences**
- Boot 4 brings Jackson 3 and Spring Security 7.
- Developers must check the Spring AI 2.x and springdoc 3.x docs for API changes compared with 1.x/2.x tutorials.
- Items marked **VERIFY** in the other docs must be confirmed during implementation and recorded here.

## ADR-002: Modular monolith
**Status:** Accepted

**Decision:** one deployable application, with packages by feature under `io.graphspindle` (ARCHITECTURE §2) and boundaries enforced by ArchUnit (or Spring Modulith if it is available for Boot 4.2).

**Rationale:** the product requirement is a single application, and the team is small. Shared services guarantee REST/MCP parity.

**Consequences:** there is no inter-service messaging. Modules talk through Java interfaces only.

## ADR-003: neo4j-harness instead of Testcontainers for integration tests
**Status:** Accepted

**Context:** AGENTS.md proposes Testcontainers by default, but the development machine has **no Docker**.

**Decision:** integration tests use `org.neo4j.test:neo4j-harness` 2026.10.x in-process. `compose.yaml` is still provided for users who run Neo4j in Docker, but no test depends on it.

**Consequences**
- The harness is Community edition, so the schema must avoid Enterprise-only features: no node-key, property-existence or property-type constraints, no multiple databases, and no RBAC.
- The harness adds heavy test dependencies, and transitive conflicts may need exclusions.
- If Docker becomes available, a Testcontainers profile can be added. The harness remains the default.

## ADR-004: Multi-brain isolation through a `brainId` property in one database
**Status:** Accepted

**Options considered**
1. Database per brain. Rejected: it is Enterprise-only and unavailable in the harness.
2. A label per brain. Rejected: unbounded labels, and no composite constraints across labels.
3. A `brainId` on every node and edge. Chosen.

**Decision:** composite uniqueness constraints include `brainId`, and every store method takes `BrainScope`. A static test asserts that every Cypher statement contains `$brainId`. Fulltext queries filter by `brainId` in the same statement. The LLM only sees opaque per-ingestion handles.

**Consequences**
- Isolation is enforced by the application and tests, not by the database. A bug in `graph` could leak data, so cross-brain ITs are mandatory in M4, M5, M6, M8 and M9.

## ADR-005: Graph schema model F (entity + reified claims + projection + provenance)
**Status:** Accepted

**Context:** five candidate models were evaluated (SCHEMA §2–§4): flat typed entities, reified claims, PKM notes, schema.org+PROV-O, and temporal fact-edges.

**Decision**
- Typed `Entity` nodes (15 core types) are the identity layer.
- `Claim` nodes are the source of truth for every relation and attribute, with status, confidence, polarity, bitemporal fields and evidence.
- Active positive claims are projected as typed edges and `attr_*` properties for query ergonomics.
- `Ingestion` and `SourceRef` nodes carry provenance. `Alias` nodes support resolution, and `Conflict` nodes make contradictions explicit.

**Rationale:** model F is the only candidate that meets provenance, non-destructive correction, contradiction handling and temporal validity while keeping one-hop queries. It scored 49 against 39–44 for the alternatives.

**Consequences**
- More writes per fact, plus a projection invariant that must be maintained transactionally and checked by `ProjectionVerifier`.
- schema.org / PROV-O remain an export mapping, not the internal vocabulary.

## ADR-006: Database-enforced claim slots (`activeKey`, `slotKey`)
**Status:** Accepted (pending VERIFY that composite uniqueness ignores missing properties on 2026.10)

**Decision:** two keys exist only while a claim is ACTIVE.
- `activeKey` deduplicates identical assertions, which become reinforcement.
- `slotKey` guarantees at most one active value for cardinality-ONE predicates.
Both have composite uniqueness constraints with `brainId`.

**Rationale:** contradictions and duplicates must be prevented under concurrency without Enterprise features.

**Consequences:** status transitions must always add or remove the keys. If the constraint semantics differ, fall back to a dedicated `(:Slot {brainId, key})` lock node per slot with a uniqueness constraint. The fallback is recorded as an ADR update.

## ADR-007: Controlled, bounded and extensible vocabulary
**Status:** Accepted

**Decision**
- The core profile `core-v1` (15 entity types, 28 relation predicates, a fixed attribute set) is defined in Java as data.
- Brains may disable core items, override the cardinality of a predicate, and add a bounded set of custom types and predicates through the admin API. The limits are 20 custom types, 30 custom relations, and 40 types and 60 predicates enabled in total.
- The LLM can never add vocabulary. Unknown output is rejected.

**Rationale:** product requirement R8. About 15 types and 30 predicates is the practical sweet spot for extraction quality on small local models.

**Consequences:** the prompt, JSON schema, validator and `/vocabulary` API are all generated from the same registry.

## ADR-008: The LLM proposes typed operations; Java validates and writes
**Status:** Accepted

**Decision**
- A two-stage pipeline: an extract call, then a deterministic candidate lookup, then a reconcile call that returns typed `MutationOperation`s (ARCHITECTURE §4).
- Then pure-Java validation, then one parameterized Cypher write transaction.
- The ingestion LLM has no tools. Evidence must be a literal substring of the input.

**Rationale:** prompt-injection resistance, determinism, testability with a fake model, and no LLM-authored Cypher.

**Consequences:** there are two LLM calls per ingestion, which is slower. A single-pass mode is planned as an option after M7.

## ADR-009: Pluggable LLM providers through Spring AI `ChatModel`
**Status:** Accepted

**Decision**
- The `llm` module depends only on `ChatModel` / `ChatClient`.
- Provider starters: Ollama (default, local), OpenAI (also used for OpenAI-compatible servers through base-url: LM Studio, vLLM, llama.cpp, Groq…) and Anthropic, selected by configuration (expected `spring.ai.model.chat`; **VERIFY**).
- Tests use a deterministic `FakeChatModel`. With no provider, ingestion returns 503 and the rest keeps working.

**Rationale:** the product requires local and remote model support, and tests must run offline.

## ADR-010: Parse model output ourselves instead of relying on `BeanOutputConverter`
**Status:** Accepted

**Decision:** take `call().content()`, parse it with Jackson into wire DTOs, map them to sealed records, and allow one repair retry. Provider-native JSON modes may be enabled as an optimization but are not relied on.

**Rationale:** polymorphic operation lists produce schemas and behaviour that are unreliable across providers. A single parser keeps the behaviour identical across providers and the fake.

## ADR-011: Identifiers
**Status:** Accepted

**Decision**
- Engine-generated UUIDv7 ids.
- A brain-scoped `canonicalKey = type:normalizedName` for entities, used as the MERGE key, with deterministic normalization (SCHEMA §7.3) that keeps symbols such as C++ and C#.
- `Alias` nodes for many-to-many name lookup.
- A reserved `person:@self` per brain for first-person statements.
- Clients and the LLM never choose ids. The LLM sees per-ingestion handles.

## ADR-012: Non-destructive corrections and conflict policy
**Status:** Accepted

**Decision**
- Default policy `EXPLICIT_CORRECTIONS_SUPERSEDE`: explicit corrections supported by evidence supersede LLM-origin claims, and implicit contradictions create a DISPUTED claim plus an OPEN `Conflict`.
- MANUAL knowledge is never superseded automatically, which raises a `PROTECTED_OVERRIDE` conflict instead.
- Nothing is hard-deleted by ingestion. Policies `ALWAYS_FLAG` and `LATEST_WINS` are configurable per brain.

**Rationale:** the requirement "do not silently merge contradictory facts or overwrite user knowledge".

## ADR-013: No raw content retention, and no fetching of references
**Status:** Accepted

**Decision**
- Ingestion stores a hash, a length and an LLM summary. Raw text is stored only if the brain opts in (`retainRawContent`, capped length).
- File paths and URLs become `SourceRef` locator + summary and are never dereferenced.
- Secrets are redacted before the LLM call.

## ADR-014: Authentication with per-brain scoped API keys
**Status:** Accepted

**Decision**
- Bearer tokens `gsk_<keyId>_<secret>`, with a SHA-256 hash of the high-entropy secret stored in `:ApiKey` nodes.
- Scopes `BRAIN_READ`, `BRAIN_WRITE`, `INGEST`, `BRAIN_ADMIN` and `ADMIN`, plus a list of brains per key.
- An admin bootstrap key from the environment.
- One Spring Security filter chain covers REST, Swagger and MCP, and `BrainAccessGuard` runs inside services.
- Unauthenticated mode is allowed only on loopback, and startup fails otherwise.
- Unknown and unauthorized brains both return 404.

**Rationale:** this is simple and self-hostable, has no external IdP, and works for agents and robots. OAuth2 / MCP authorization specifications can be layered on later.

**Consequences:** rate limiting is in-memory and per instance, which is acceptable for a single deployable.

## ADR-015: Data access through `Neo4jClient`, not SDN repositories
**Status:** Accepted

**Decision:** use Spring Data Neo4j only for auto-configuration, the transaction manager and `Neo4jClient`. All Cypher is explicit, parameterized and kept in the `graph` module. SDN `@Node` repositories are not used.

**Rationale:** dynamic but whitelisted labels, mandatory brain scoping, projection maintenance and constraint-driven MERGE semantics are clearer in explicit Cypher than in repository derivation.

## ADR-016: Jackson 3 everywhere; the proposal parser owns its mapper
**Status:** Accepted (2026-11-26, M3)

**Context:** ARCHITECTURE §2 asked to VERIFY the Jackson major version. `./mvnw dependency:tree` on Boot 4.2.0 shows `spring-boot-starter-jackson` → `tools.jackson.core:jackson-databind:3.1.8` (+ `jackson-core` 3.1.8). The annotations jar stays `com.fasterxml.jackson.core:jackson-annotations:2.21` (Jackson 3 reuses the 2.x annotation package).

**Decision**
- Use Jackson 3 (`tools.jackson.*`) for all code. Never import `com.fasterxml.jackson.databind` or `com.fasterxml.jackson.core`; `com.fasterxml.jackson.annotation.*` is the correct annotation package.
- `ProposalParser` builds its own `JsonMapper` instead of Boot's auto-configured one, because its safety settings must not change with application configuration: `STRICT_DUPLICATE_DETECTION` (duplicate keys make output malformed), `StreamReadConstraints` (depth 32, document and string length = max model output, number length 64, name length 200) and `FAIL_ON_TRAILING_TOKENS` disabled (trailing prose after the JSON object is ignored). This deviates from the ARCHITECTURE §2 note "use Boot's mapper for our own parsing"; other code (REST DTOs) should still use Boot's mapper.
- Model output is parsed as a `JsonNode` tree and mapped by hand to the sealed records, which gives precise per-operation errors.

**Still to VERIFY (M6):** which Jackson major Spring AI 2.1 uses internally. Spring AI is only imported as a BOM so far, so no Spring AI artifact is on the classpath yet.

## ADR-017: M3 vocabulary and validation details
**Status:** Accepted (2026-11-26, M3)

Gaps and inconsistencies in SCHEMA/ARCHITECTURE resolved while implementing M3:

1. **Name length.** SCHEMA §7.3 both truncates `norm()` to 200 code points and says results longer than 200 are rejected. `Normalizer.norm` truncates (as the M3 test plan requires); the validator rejects raw names/aliases over 200 code points with `INVALID_VALUE`, and empty normalized names with `NAME_INVALID`.
2. **Unknown fields.** Envelope-level unknown properties are recorded in `ParsedProposal.unknownFields` and ignored. An unknown property inside an operation (including nested `EntityTarget`s and claim bodies) is recorded **and** rejects that operation with the new code `UNKNOWN_FIELD`. This is stricter than M3's "unknown fields are recorded", on the lead's instruction, and blocks smuggled fields such as `brainId`.
3. **New rejection codes.** `UNKNOWN_FIELD` (above) and `DEPENDENCY_REJECTED` (an operation referencing a `ref` whose `create_entity` was rejected; ARCHITECTURE §5 says such ops are dropped transitively but names no code). Structural problems found by the parser (missing required field, wrong JSON type, both/neither of `ref`/`id`, both/neither of `object`/`value`) use `INVALID_VALUE`.
4. **`TYPE_INCOMPATIBLE_MATCH`.** `EntityCandidate` carries `mentionTypes`, the extracted types of the mentions stage 2 retrieved it for. Using a handle whose type is incompatible (SCHEMA §6.2) with all of its mention types is rejected; so is `propose_merge` across incompatible types. Candidates with empty `mentionTypes` (the speaker, context-only candidates) are not checked.
5. **`create_entity` that matches an existing entity.** It becomes a reference (adjustment `CREATE_BECAME_REFERENCE`) when a candidate has the same `canonicalKey`, or else when exactly one candidate has the same normalized name and a compatible type (the existing type wins, SCHEMA §6.2). Its new aliases become `AddAliasCommand`s; its description is not applied (`DESCRIPTION_IGNORED`; use `update_entity`). Alias-only matches are left to M7's `DuplicateSuspicion`. A second `create_entity` with the same canonical key in one proposal resolves to the first.
6. **Compatibility group names** are `TECHNICAL`, `WORK`, `ACTOR`, `OBJECTIVE`. A custom type's `compatibilityGroup` must be null or one of them.
7. **Counting predicates for the 60 limit:** relation predicates plus distinct attribute predicate names (core = 28 + 13 = 41). Only core predicates can be listed in `disabledPredicates` (custom ones are removed instead). Cardinality overrides apply to relation predicates and attribute names, core or custom.
8. **Brain subsets.** When types are disabled, each predicate's source/target types are restricted to enabled ones and a predicate with no possible pair is dropped from the brain vocabulary (e.g. `LOCATED_IN` without `Place`). Existing entities of a disabled type are read-only for ingestion: writing to them (`update_entity`, `add_alias`, claim subject) is rejected with `UNKNOWN_TYPE`, and using them as a claim object fails the type-pair check.
9. **Defaults not given by the docs:** `maxClaimsPerSubject` = 30 per ingestion; custom STRING attributes default to `maxLength` 200; custom type and relation descriptions are required (≤ 200); raw model output is refused above 2,000,000 characters and above 500 operations (operations beyond `maxOperations` = 60 are rejected individually with `LIMIT_EXCEEDED`).
10. **Evidence matching** (`EvidenceChecker`): NFKC, control/format characters removed, typographic quotes and dashes folded to ASCII, lowercase (`Locale.ROOT`), whitespace collapsed, then a plain substring test against the redacted content and each source summary. Normalized quotes shorter than 3 characters are rejected because they prove nothing. *(Superseded by ADR-021: stricter minimum plus a relevance rule.)*
11. **Order of checks** is: handle resolution → predicate → self-loop → type pair → values/lengths → secrets → evidence → confidence → per-subject limit → `PROTECTED_TARGET`. So a protected-override conflict is raised only by an otherwise valid, evidenced operation, and injected text cannot spam conflicts.
12. **Claim keys in the plan.** New entities have no id at validation time (they are MERGEd by `canonicalKey` at apply time), so `ResolvedClaim` carries everything needed and `ResolvedClaim.keys(brainId, subjectId, objectId)` computes `activeKey`/`slotKey` at apply time. `contradictsClaimId` and `reinforcesClaimId` are annotated only against candidate-set claims and only for plain `assert_claim`; the conflict policy (SCHEMA §9.4) is applied in M6/M7.
13. **Where shared enums live.** `Polarity`, `Origin`, `ClaimKind`, `PartialDate`, `Normalizer`, `Hashing`, `Limits` are in `common`; `ValueType`, `Cardinality`, `ClaimKeys` and `AttributeValues` are in `vocabulary` (graph and knowledge code need them without depending on ingestion). `RejectionCode`/`ValidationIssue` are in `ingestion.validation` and also used by `ingestion.ops.ProposalParser`, an accepted package cycle inside the ingestion module.
14. **Lenient parsing:** op names are trimmed and case-insensitive; numeric/boolean `value`s become strings; numeric-string `confidence`, string `"true"/"false"` for `explicitCorrection` and case-insensitive `polarity` are accepted; a claim body may repeat `"op":"assert_claim"`. The first fenced code block is tried first, then up to 8 `{` positions in the raw text.
15. **Summary** is truncated to 1000 code points and secrets in it are redacted (adjustment `SUMMARY_ADJUSTED`).
16. **Deferred:** `GET/PUT /brains/{id}/vocabulary` and `VocabularyApiIT` need `BrainService`/`BrainConfig` (M2) and are not part of this commit. `VocabularyRegistry.forBrain` currently takes a `VocabularyConfig`; the brain-id lookup is added with M2. No Spring beans are registered for the M3 classes yet (all have no-arg constructors).

## ADR-018: Plain Neo4j Java driver instead of Spring Data Neo4j's `Neo4jClient`
**Status:** Accepted (2026-11-26, M1). Supersedes the data-access part of ADR-001 and ADR-015.

**Context:** the integration spike (INTEGRATION_NOTES §1a) showed that Boot 4.2 ships `spring-boot-starter-neo4j` (driver 6.3.0, `Driver` auto-configuration and health indicator) separately from `spring-boot-starter-data-neo4j`. `Neo4jClient` and the SDN transaction manager exist only in the latter.

**Decision**
- Depend on `spring-boot-starter-neo4j` only. All Cypher is hand-written, parameterized and lives in `io.graphspindle.graph`; transactions are driver-managed (`session.executeWrite/executeRead`), not `@Transactional`.
- ArchUnit (1.5.1, latest on Maven Central) enforces the module rules instead of Spring Modulith: no `org.neo4j..` dependency outside `graph`, `api` does not touch `graph`/`llm`, `llm` and `graph` are independent, `internal` packages are module-private, and there are no package cycles except the accepted `ingestion.ops` <-> `ingestion.validation` cycle (ADR-017 §13).

**Consequences:** ARCHITECTURE §12 "`@Transactional` with the SDN transaction manager" becomes "one driver-managed transaction per store facade call". Unit tests without Neo4j set `management.health.neo4j.enabled=false` (INTEGRATION_NOTES pitfall 3).

## ADR-019: M2 brain management details
**Status:** Accepted (2026-11-26, M2)

1. **`BrainScope` lives in `common`, `BrainStore` in `graph`.** ARCHITECTURE §2 puts `BrainScope` and `internal/BrainStore` in `brain`, but Cypher may only live in `graph` and every graph store takes a `BrainScope`; keeping both in `brain` would create a `brain` <-> `graph` cycle. `BrainStore` persists `configJson` as an opaque string; `brain.BrainService` owns `BrainConfig` and its (de)serialization. `ConflictPolicy` is in `common` for the same reason (graph apply needs it in M4).
2. **Scoped transactions.** `graph.internal.ScopedTx` binds `$brainId` itself, refuses statements without `$brainId` and refuses caller-supplied `brainId` parameters. Only schema statements and `BrainStatements.LIST_ALL` run unscoped.
3. **Error codes.** Two codes added to ARCHITECTURE §13: `ALREADY_EXISTS` (409, duplicate brain id) and `PRECONDITION_FAILED` (412, stale `If-Match`). Errors are RFC 9457 `application/problem+json` with `type = urn:graphspindle:problem:<code>` and a `code` property; validation problems carry an `issues` array. Invalid brain ids on lookup return 404 `BRAIN_NOT_FOUND` (same as unknown), on create 400 `VALIDATION_FAILED`.
4. **Optimistic locking.** `ETag`/`If-Match` carry `configVersion` (`"3"`, `W/"3"` and `3` accepted). `If-Match` is optional; when present a mismatch is 412. Every successful PATCH or vocabulary PUT increments `configVersion` (name/description changes too, so one counter protects the whole brain). The update takes the brain node's write lock (no-op `SET/REMOVE`) before comparing, verified by a concurrent test (8 parallel PATCHes with the same version: exactly one 200).
5. **PATCH semantics.** Null fields are unchanged; `config` replaces the whole `BrainConfig` (missing fields take defaults); a description cannot be cleared through PATCH in the MVP.
6. **`BrainConfig` defaults** not given by the docs: `allowLlmSupersession=true`, `rawContentMaxChars=32000` (≤ 100,000), `dedupWindowHours=24` (≤ 720), brain limits within maxOperations 1..200, maxNewEntities 0..100, maxAliasesPerEntity 0..20, maxClaimsPerSubject 1..100. `duplicateSuspicionScore` is left to M7, where fulltext scores are calibrated.
7. **Strict JSON input.** `spring.jackson.deserialization.fail-on-unknown-properties=true` for API bodies (a misspelled `enabledTypes` must not be silently ignored). Stored configs are read with unknown properties ignored, so removing a config field later never makes a brain unreadable.
8. **Self entity.** `(:Entity:Person {origin: SYSTEM, canonicalKey: 'person:@self', normalizedName: '@self', name: ownerName ?: 'Me'})` is created in the same transaction as the Brain node. No `Alias` is created for it (first-person mentions resolve through `isSpeaker`, not through the alias "me").
9. **Deletion** is synchronous in the MVP: the brain is set to `DELETING` (other updates get 409 `CONFLICT_STATE`), then `MATCH (n) WHERE n.brainId = $brainId CALL (n) { DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS` runs in an auto-commit transaction. `confirm` must equal the id (400 otherwise); an unknown brain is 404.
10. **Schema initialization** runs as an `ApplicationRunner` and can be disabled with `graphspindle.graph.initialize-schema=false` (only the DB-less context test does this).

## ADR-020: M4 graph store, verified Neo4j behaviour, and write-semantics details
**Status:** Accepted (2026-11-26, M4). Resolves the SCHEMA §8/§12 VERIFY items and the ADR-006 pending note.

**VERIFY outcomes** (empirical, `CypherFeaturesIT` and `SchemaInitializerIT` against neo4j-harness 2026.10.2, Community, default Cypher language of the harness database):
1. **Composite uniqueness ignores nodes missing a property.** Two `Claim` nodes of one brain without `activeKey` are accepted, the same `activeKey` in two brains is accepted, a duplicate `(brainId, activeKey)` fails with `Neo.ClientError.Schema.ConstraintValidationFailed`, and `REMOVE c.activeKey` frees the key. ADR-006 holds; the `(:Slot)` lock-node fallback is **not** needed.
2. **`IF NOT EXISTS`** makes all 13 constraints, 8 range indexes and 3 fulltext indexes idempotent (initializer run twice, `SHOW CONSTRAINTS/INDEXES` unchanged).
3. **Dynamic labels and relationship types** work: `SET n:$($type)` and `MERGE (a)-[r:$($type) {...}]->(b)` (also `CALL (n) { ... }` scoped subqueries). A parameter is always a single token, so even a hostile value cannot inject Cypher (it would only become an odd label), but tokens are still accepted only from the brain vocabulary and must be plain identifiers that are not system names (`graph.internal.Tokens`); the backtick fallback is not needed.
4. **Fulltext:** `db.index.fulltext.queryNodes(index, query, {limit: n})` option-map syntax works. Indexes are created with `OPTIONS {indexConfig: {`fulltext.analyzer`: 'standard-no-stop-words'}}` so "The Who" and "the" remain searchable (verified). User text is escaped (`graph.internal.Lucene`) and every query filters `node.brainId = $brainId` in the same statement with 5x oversampling.
5. `SET n += {attr_x: null}` removes the property, so attribute projection needs no dynamic property syntax.

**Design decisions**
1. **Module layout.** Public `graph` API: `GraphStore.apply`, `GraphReader`, `IngestionStore`, `BrainStore`, `ProjectionVerifier`, `SchemaInitializer` and records. Brain-scoped stores (`EntityStore`, `AliasStore`, `ClaimStore`, `ProjectionStore`, `SourceRefStore`, `ConflictStore`) live in `graph.internal` and take a `ScopedTx` (bound to one `BrainScope`) instead of a `BrainScope` parameter. All Cypher is in `graph.internal.*Statements` constants; `CypherScopingTest` checks every constant contains `$brainId` (allowlist: `BrainStatements.LIST_ALL`, schema statements) and scans all main sources for Cypher outside those classes.
2. **ArchUnit cycle rule** now checks package slices and forbids every cycle except edges between packages of the same module (`graph` <-> `graph.internal`, `ingestion.ops` <-> `ingestion.validation`). `graph` depends on `ingestion.validation` (it applies a `ValidatedPlan`), so the future `ingestion.pipeline` -> `graph` dependency is legal as long as `ingestion.validation` never depends on `graph` or `ingestion.pipeline` (the rule enforces this).
3. **Apply order** per ARCHITECTURE §3 stage 5 by command phase (creates; aliases/updates; sources; asserts; supersede/retract; conflicts/merges/protected overrides), stable in proposal order within a phase; then consequence rules, `MENTIONED_IN` edges (one per entity per ingestion, `mentionCount` +1 only for a new edge), then the Ingestion record (`APPLIED`, or `NO_CHANGES` when nothing changed). The Ingestion must exist in status `RECEIVED` (created by `IngestionStore.createReceived` in its own transaction; duplicate `requestId` -> `IN_PROGRESS`); applying twice is refused with `CONFLICT_STATE`. After a rollback `IngestionStore.markFailed` records `FAILED` in a separate transaction.
4. **Retry once** on `ConstraintValidationFailed` re-runs the whole attempt in a new transaction; MERGE by canonicalKey and the activeKey/slotKey lookups then see the concurrent winner (concurrency ITs: 8 threads creating one entity -> 1 node, 8 identical claims -> 1 claim with evidenceCount 8, 8 conflicting ONE-slot claims -> 1 ACTIVE + 7 DISPUTED + 7 conflicts). Transient errors (deadlocks) are retried by the driver's managed transactions.
5. **Claims carry `cardinality`** (an addition to SCHEMA §5.4) so projections and the verifier do not depend on the current vocabulary. `attr_<name>` holds the single value for ONE and a sorted list for MANY attribute predicates. Projections are recomputed from claims for the touched slot after every status change.
6. **Apply-time conflict routing** (DB truth, not just the candidate set): an assert first reinforces an ACTIVE claim with the same `activeKey` (`EVIDENCED_BY` merged per ingestion, `evidenceCount` = number of evidencing ingestions, confidence = max); otherwise a contradiction is the ACTIVE claim in the same ONE slot or the same triple with the opposite polarity. Default policy: the incoming claim is `DISPUTED` (no keys, no projection) with a `CONTRADICTION` conflict (`INVOLVES` EXISTING/INCOMING, `RAISED_BY`). `LATEST_WINS` supersedes when the existing claim is non-MANUAL and from the same principal. `supersede_claim` supersedes only when `explicitCorrection` is true, policy is not `ALWAYS_FLAG` and `allowLlmSupersession` is on (or `LATEST_WINS` with the same principal); otherwise the replacement is disputed. A supersede whose target is no longer ACTIVE is applied as a plain assert. A NEGATIVE replacement with `validFrom` sets the old claim's `validTo` (SCHEMA §9.4 last row). MANUAL claims and locked descriptions are re-checked at apply time and raise `PROTECTED_OVERRIDE` (incoming replacement stored `DISPUTED`). A plain assert contradicting a MANUAL claim is a `CONTRADICTION`, not a `PROTECTED_OVERRIDE` (it does not try to supersede).
7. **Consequence rule implemented now** (SCHEMA §10.3): an ACTIVE `REPLACES` claim created in the plan supersedes the replaced entity's ACTIVE `status` claim with a SYSTEM claim `status = SUPERSEDED` (only where the vocabulary allows that value), linked by `SUPERSEDES`; a MANUAL status claim raises `PROTECTED_OVERRIDE` instead.
8. **Retraction** stores `statusReason`, `statusEvidence` and `statusChangedByIngestionId` on the claim (additions to SCHEMA §5.4); `DISPUTED` claims can be retracted too. Duplicate `propose_merge` for an already OPEN pair is skipped. Merged ids that become a self-loop are skipped.
9. **Cross-brain safety at apply time:** every id from a plan is re-resolved with `MATCH (... {brainId: $brainId, id: $id})`; a foreign or unknown id fails the whole transaction with `NOT_FOUND` (`CrossBrainIT`). Existing entity ids follow `MERGED_INTO` (≤ 5 hops) to the survivor.
10. **Deferred to M5:** the full entity merge (re-pointing claims, key recomputation, alias moves; only the `MERGED_INTO` tombstone exists now), manual (`Origin.MANUAL`) write paths beyond what `ApplyRequest.origin` already supports, conflict resolution, retrieval queries. The admin projection-check endpoint arrives with M5's REST work.

## ADR-021: M3 hardening after QA review (F1–F13)
**Status:** Accepted (2026-11-26). Amends ADR-017 and SCHEMA §6.4, §7.3–§7.5, §11; ARCHITECTURE §5.

A QA review found validation gaps in the M3 code. Decisions taken while fixing them (regression tests in `M3HardeningTest`):

1. **NUMBER bounds (F1).** After `stripTrailingZeros`, a NUMBER must have precision ≤ 38 and |scale| ≤ 38, checked *before* `toPlainString()`. `"1E+999999999"` used to build a billion-digit string (OOM); `ArithmeticException` from scale overflow is treated as invalid.
2. **Secrets in the folded form (F2).** `SecretScanner.containsSecret` also scans NFKC with control/format characters removed (`Normalizer.normalizeWhitespace`); `redact` returns the redacted folded text when a secret is only visible there. The validator also scans computed `normalizedName`s, alias norms and `valueNorm`s, because those are what is stored.
3. **Diacritics (F3).** Only the combining diacritical mark blocks are removed (SCHEMA §7.3 step 6); other `\p{Mn}` (kana voicing marks, Indic vowel signs ...) are kept. Latin results are unchanged.
4. **No silent truncation (F4, NFKC expansion).** `Normalizer.norm()` keeps its 200-code-point bound for compatibility, but validators use `normUntruncated()`: a name/alias whose normalized form exceeds 200 code points is rejected with `NAME_INVALID`; STRING `valueNorm` and "other" source locators are stored untruncated, so long values that share a 200-character prefix no longer share an `activeKey` and are not misreported as reinforcements.
5. **In-plan compatible-type unification (F5).** A `create_entity` first matches an earlier create of the same canonical key, else the *first* earlier create with the same normalized name and a compatible type; the earlier type wins (as the existing type wins for candidates, SCHEMA §6.2).
6. **Case-insensitive vocabulary names (F6).** Custom type names are compared ignoring case against core types, reserved labels and each other (the canonical key lowercases the type, so `TECHNOLOGY` would collide with `Technology`). Relation predicates and attribute names are compared the same way (per type for attributes, and attributes vs custom relations).
7. **Read-only types everywhere (F7).** `checkWritable` now also guards `retract_claim`, `supersede_claim`, `flag_conflict` (subject and, when known, object of the targeted claim), `propose_merge` (both entities) and `link_source` (target).
8. **Evidence must be substantial and relevant (F8).** Chosen rule: a normalized quote needs ≥ 6 letters/digits and either ≥ 2 words or ≥ 12 letters/digits (text in scripts written without spaces — Han, kana, Thai, Lao, Khmer, Myanmar, Tibetan — needs ≥ 4 letters). In addition, the quote or the sentence(s) containing it (split at `. ! ?` followed by a space, or `。！？`) must mention one of the claim's terms: subject or object name, alias or normalized name; first-person words (`I`, `we`, `my` ...) for the self entity; for attribute claims the raw and canonical value. A multi-word name also matches by any significant word (≥ 3 characters, not a small stop list such as `use`, `for`, `the`), so a decision named "Use Apache License 2.0 for GraphSpindle" is mentioned by "I decided to use Apache 2.0." Matching folds case and diacritics and respects word boundaries (not inside other words, except for scripts without spaces). For `retract_claim` the terms come from the targeted claim's subject/object candidates and `valueNorm`; when none of them is in the candidate set only the quote rule applies. All SCHEMA §10 example fixtures satisfy the rule unchanged. One `EvidenceCheckerTest` example (`"ｊａｖａ"`, a single 4-letter word) encoded the old 3-character minimum and was replaced by a full-width two-word quote that keeps the NFKC intent.
9. **URL attributes reject user information (F9)** (`https://admin:hunter2@...`), detected by parsing the authority by hand.
10. **Parser: only top-level objects (F10).** After a failed or non-envelope parse attempt at a `{`, the scanner skips to after that object's matching `}` (string-aware brace matching; an unclosed brace is treated as prose and only skips itself). An envelope rejected for duplicate keys can therefore no longer be rescued by a nested object with `operations`; nor is an envelope nested in a wrapper object accepted.
11. **URL normalization parses the authority by hand (F11)**, so registry-based hosts (`exa_mple.com`, for which `URI.getHost()` is null) are lowercased, lose the default port, and user info is no longer duplicated.
12. **Adjustments only for accepted ops (F12).** Adjustments are buffered per operation and committed only when it is accepted. (A `PROTECTED_OVERRIDE` command is still emitted for a rejected op, by design.)
13. **`flag_conflict` targets the same slot (F13).** The incoming claim must have the flagged claim's subject and predicate, else `NOT_SLOT_CONFLICT` (as for `supersede_claim`).
14. **Output schema:** the `value` field's `maxLength` is `AttributeValues.MAX_VALUE_LENGTH` (2000, the longest URL attribute) instead of 500; per-attribute limits are still enforced by the validator.


## ADR-022: M2/M4 hardening after QA review (brain lock, vocabulary guard, scoped fulltext, strict input)
**Status:** Accepted (2026-11-26). Amends ADR-019 §4/§7/§9, ADR-020 §4/§6 and SCHEMA §8, §11; ARCHITECTURE §8, §12. Regression tests: `QaAdversarialIT`, `QaRestIT`, `GraphFixesIT`, `RequestLimitsIT`.

1. **Every brain write takes the Brain lock first (F2).** `graph.internal.BrainLock.lockActive` (no-op `SET/REMOVE` on the Brain node, then re-read `status`) is the first statement of `GraphStore.apply`, of manual knowledge writes and of `IngestionStore.createReceived` (`CREATE_RECEIVED` locks before it checks `ACTIVE`). `MARK_DELETING` needs the same lock, so it waits for in-flight writes, and every later write sees `DELETING` and fails with `CONFLICT_STATE`. Consequence: applies into one brain are serialized (they are short; LLM calls happen before). Lock order is always Brain first. `DELETE_ALL` now excludes the Brain node; `BrainStore.delete` repeats it (≤ 5 passes) until `DELETE_BRAIN_IF_EMPTY` (locks the Brain node, re-counts, deletes it only when nothing of the brain is left) succeeds, so a re-created brain can never inherit nodes. The QA test's original timing (DELETING visible while the apply runs) is impossible now; it asserts instead that the apply never commits while DELETING is visible and that nothing is left.
2. **Removing custom vocabulary in use is refused (F1).** `BrainService` compares the old and new resolved vocabularies on PATCH (config) and vocabulary PUT: custom entity types, custom relation predicates and custom attribute names that disappear are counted (`BrainStore.usage`: ACTIVE entities per type, ACTIVE or DISPUTED claims per predicate); any use gives `409 CONFLICT_STATE` with an `inUse` map. Disabling *core* types and predicates stays allowed (SCHEMA §11). `force=true` (archive users) is not implemented in the MVP. Residual race: an apply that resolved the old vocabulary before the PUT can still write a claim with the removed predicate; that is harmless because of item 3.
3. **Projection maintenance validates stored predicates as identifiers only.** `ProjectionStore.sync/recomputeAttribute` no longer require the predicate to be in the *current* vocabulary (`Tokens.storedPredicate`): the predicate of a stored claim was vocabulary-checked when it was written. Retracting or superseding a claim whose predicate has since been removed therefore works. New claims are still vocabulary-checked (`Tokens.relationType/attributeProperty` in claim creation).
4. **Fulltext is scoped inside Lucene (F3).** Entity, Claim and SourceRef nodes carry `ftScope` = brainId with `-` replaced by `_` (one token for the standard analyzer; distinct ids give distinct tokens, unlike a phrase query on `brainId`, where `a-b` would match inside `x-a-b`). The property exists only while a node is searchable: ACTIVE entities, ACTIVE claims, all source refs (removed on MERGED/ARCHIVED and when a claim leaves ACTIVE). The fulltext indexes include `ftScope`, and `graph.internal.Lucene.scoped` builds `+ftScope:<token> +(name:(terms) description:(terms))`, so user terms never match the scope field. The `WHERE node.brainId = $brainId` filter stays as defence in depth. Search terms also include adjacent-word and all-word concatenations so "graph spindle" matches "GraphSpindle". Existing fulltext indexes keep their names; a database created before this change must drop and re-create `entity_text`, `claim_text` and `source_text` (pre-release, no migration).
5. **Vocabulary PUT has no lost update (F4).** Config writes without `If-Match` use the version they read as the expected version and retry (≤ 5) on `PRECONDITION_FAILED`, recomputing the change (and the in-use guard) from the fresh config. With `If-Match` a mismatch is still 412.
6. **Strict JSON and a body cap (F5).** `spring.jackson.read.strict-duplicate-detection=true` and `spring.jackson.deserialization.fail-on-trailing-tokens=true` (400 `VALIDATION_FAILED`). `api.rest.RequestSizeLimitFilter` refuses a `Content-Length` above `graphspindle.api.max-request-bytes` (default 1 MiB, env `GRAPHSPINDLE_MAX_REQUEST_BYTES`) before reading, and cuts off streamed bodies at the cap; both answer `413 LIMIT_EXCEEDED`. ARCHITECTURE §8's 256 KB was below a maximal legal ingestion request (32,000 characters of content plus 10 sources, up to 4 bytes per character in UTF-8 and 6 per escaped character), hence 1 MiB.
7. **Repeated identical contradictions reinforce the open dispute.** Before a DISPUTED claim and a conflict are created, `ConflictStatements.FIND_OPEN_DISPUTE` looks for an OPEN conflict of the same kind whose EXISTING claim is the same and whose INCOMING claim is DISPUTED with the same triple and polarity. If found, evidence is added to that claim (outcome `DISPUTE_REINFORCED`) and no new claim or conflict is created. Once the conflict is resolved, a new contradiction opens a new one. `ClaimStoreIT` was adjusted accordingly: its repeated ALWAYS_FLAG correction now asserts `DISPUTE_REINFORCED` on the earlier dispute (and a different value still yields `CLAIM_DISPUTED`).
8. **A supersede never leaves the slot empty.** If the replacement would itself be disputed by another ACTIVE claim (the opposite polarity of the replacement triple, or another claim in its slot), nothing is superseded: the replacement is stored DISPUTED with a CONTRADICTION conflict against the blocking claim, and the target stays ACTIVE. A replacement identical to another ACTIVE claim is not blocked (it reinforces it after the supersession).

## ADR-023: M5 manual knowledge, conflict resolution and retrieval
**Status:** Accepted (2026-11-26, M5). Tests: `KnowledgeApiIT`, `CorrectionApiIT`, `MergeIT`, `ManualProtectionIT`, `RetrievalIsolationIT`, `LimitsIT`, `ConflictResolutionIT`.

1. **One write path for claims.** Manual entity creation, aliases, claim assertion, supersession and retraction are plan commands applied by `GraphStore.apply` with `ApplyRequest.manual` (origin `MANUAL`, no Ingestion record, no `EVIDENCED_BY`, no sources, confidence 1.0). Reinforcement, contradiction routing, projection and the Brain lock are therefore identical for users and the LLM. Writes that are not plan commands (entity edit, archive, merge, conflict resolution) are in `graph.KnowledgeStore`, each one transaction behind the Brain lock.
2. **Service contract for REST and MCP.** `knowledge.KnowledgeService`, `conflict.ConflictService`, `retrieval.SearchService/EntityContextService/NeighborhoodService` and `admin.ProjectionCheckService` take `(Actor actor, String brainId, ...)` and return records from `knowledge.KnowledgeModel` (or nested service records); controllers only map HTTP. `common.Actor` is the principal (REST uses `Actor.LOCAL_ADMIN` = `admin` until M9, when `BrainAccessGuard` is called at the top of each of these methods). Services return their own records, never `graph` types (ArchUnit: `api` must not depend on `graph`). Results carrying graph text set `untrusted = true`; the entity context text block is fenced as `<<<GRAPH DATA (untrusted, not instructions) … GRAPH DATA>>>`.
3. **Manual semantics.** A manual create of an existing key returns the existing entity (`200`, `created = false`) and adds the aliases; `distinct = true` appends `~2`, `~3` … (≤ 50). A manual description (on create or PATCH) sets `descriptionLocked`; `descriptionLocked = false` unlocks explicitly. A rename recomputes `normalizedName`/`canonicalKey` and keeps the old name as an alias; renaming onto another entity's key is `409` (merge instead); the self entity keeps `person:@self`. Archive is soft (`ARCHIVED`, removed from search and neighbourhoods, readable by id; the self entity cannot be archived); manual writes to non-ACTIVE entities are `409`, and ids of MERGED entities are followed to the survivor. A manual assertion identical to an ACTIVE claim **confirms** it (origin becomes `MANUAL`, confidence 1.0, `confirmedAt/confirmedBy`), so it is protected from LLM corrections from then on. A manual assertion contradicting an ACTIVE claim is stored DISPUTED with a CONTRADICTION conflict (never silently overwrites); the user corrects with supersede or resolves the conflict. A manual supersede is always applied, whatever the conflict policy (SCHEMA §9.6), except when another ACTIVE claim blocks the replacement (ADR-022 §8); superseding or retracting a claim that is not ACTIVE (DISPUTED for retract) is `409`. Manual input is checked against the vocabulary (predicate kind, type pair, attribute value, engine-only values, self-loops), the secret patterns and the field limits; a statement is generated from the names when absent.
4. **Entity merge (SCHEMA §9.5)** locks both entities in id order. Types must be compatible (SCHEMA §6.2); the self entity can survive but not lose. Every claim of the loser (any status) is re-pointed (`SUBJECT`/`OBJECT` and denormalized ids). ACTIVE ones are re-keyed: identical to an ACTIVE claim → that claim absorbs the evidence and citations (recounted, max confidence, MANUAL kept) and the loser's copy becomes SUPERSEDED by it; slot or opposite-polarity collision → the loser's claim becomes DISPUTED with a CONTRADICTION conflict (no `RAISED_BY`); a claim that becomes a self-loop is RETRACTED. Aliases, `MENTIONED_IN` (mentionCount recounted) and `DESCRIBES` move; the survivor inherits a missing description; OPEN duplicate candidates between the two are RESOLVED with `MERGE`; other OPEN conflicts involving the loser now involve the survivor; attribute projections are rebuilt and the loser loses its `attr_*`; then `MERGED_INTO` and `status = MERGED`.
5. **Conflict resolution.** `KEEP_EXISTING`: the DISPUTED incoming claim is RETRACTED. `ACCEPT_INCOMING`: the existing claim (if ACTIVE) is SUPERSEDED, the incoming claim is re-keyed and ACTIVE (projected, searchable) with `SUPERSEDES` to the existing one; any other blocking ACTIVE claim makes it `409`; a PROTECTED_OVERRIDE without an incoming claim (blocked retraction, locked description) cannot be accepted — the user retracts or edits directly. `KEEP_BOTH`: only cardinality-MANY claims of the same fact with opposite polarity whose validity ranges do not overlap (`[validFrom, validTo)` by sort key; optional `validFrom`/`validTo` edits of the incoming claim in the request). `MERGE` (duplicate candidates): `survivorId` must be one of the candidates, default `CANDIDATE_B` (the existing entity a proposal points at). `NOT_DUPLICATE` sets status `DISMISSED`; every other resolution sets `RESOLVED`, with `resolution`, `resolvedBy`, `resolvedAt`, `resolutionNote`. A resolution that does not fit the conflict kind is `400`; resolving a non-OPEN conflict is `409`. The REPLACES consequence rule is not re-run on ACCEPT_INCOMING (M7).
6. **Retrieval.** Search: exact alias of `norm(q)` (score 1.0), then Lucene over entity name/description (weight 0.95), ACTIVE claim statements (0.8, credited to the subject) and source title/summary (0.6, credited to described entities and subjects of citing claims), each Lucene score mapped to `w·s/(1+s)`; merged by entity with `matchedBy` reasons; ACTIVE entities only; `types` must be enabled types. Limits: `q` ≤ 500 characters, `limit` ≤ 50 (`400 LIMIT_EXCEEDED` above). Neighbourhood: projection edges only (relationships with a `claimId` whose type is a vocabulary relation predicate), undirected, depth 1–2 (`400 LIMIT_EXCEEDED` above 2), ACTIVE entities only (also as intermediate hop), ≤ 200 nodes and ≤ 1000 edges, plus the projection edges among the returned nodes. Entity context: ACTIVE claims where the entity is subject *or* object (newest first, ≤ `maxClaims`), each with ≤ 3 cited sources; DISPUTED claims with their OPEN conflicts; OPEN conflicts of the entity and its claims; ≤ 10 describing sources. Claim history is the connected `SUPERSEDES` component (both directions, ≤ 50 hops), oldest first, each entry with its links. `GET /claims/{id}` is the claim's provenance: evidence (ingestion, quote, confidence, principal), cited sources, supersession links and conflicts.
7. **Paging and ids.** Keyset pagination by id (UUIDv7 sorts by creation time); cursors are validated as id characters; ids longer than 64 characters are treated as not found. Unknown or other-brain ids are `404 NOT_FOUND` on every endpoint (`RetrievalIsolationIT`).
