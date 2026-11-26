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
