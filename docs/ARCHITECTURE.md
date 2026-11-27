# GraphSpindle architecture

Status: design. This document describes the target design, not implemented features. The graph model is in [SCHEMA.md](SCHEMA.md), the build order in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md), and the rationale in [DECISIONS.md](DECISIONS.md). Text marked **VERIFY** must be checked against the official documentation for the pinned versions before anyone relies on it.

## 1. Shape

GraphSpindle is one Spring Boot 4.2 application (Java 21), structured as a modular monolith. It has three inbound adapters: REST (Spring MVC with springdoc Swagger UI), MCP (Spring AI MCP server, webmvc transport), and Actuator health. All of them call the same application services. Its outbound adapters are Neo4j (Java driver via Spring Data Neo4j's `Neo4jClient`) and a Spring AI `ChatModel`.

```
api.rest ─┐                       ┌─> llm (LlmGateway → ChatClient → ChatModel)
          ├─> application services ┤
api.mcp ──┘   (brain, ingestion,   └─> graph (stores, Cypher, transactions) ─> Neo4j
               retrieval, conflict,
               knowledge mgmt)
security: one filter chain in front of both /api/** and /mcp/**; BrainAccessGuard inside every service method.
```

## 2. Package layout (base `io.graphspindle`)

Organize by feature. Each top-level package is a module. Inter-module calls go only through the types in the module root (its public API). Subpackages `internal` are module-private by convention, and an ArchUnit test enforces that. **VERIFY:** whether Spring Modulith compatible with Boot 4.2 is available. If it is, Modulith's `ApplicationModules.verify()` can replace ArchUnit, but it is not required.

```
io.graphspindle
├── GraphSpindleApplication
├── common            ids (UuidV7), Clock, Normalizer (SCHEMA §7), Hashing, errors (GraphSpindleException hierarchy), Limits, PartialDate
├── brain             BrainService, BrainConfig (record), BrainScope (value type: validated brainId), internal/BrainStore
├── vocabulary        VocabularyRegistry, CoreProfile + VocabularyDefinitionLoader (core-v1.yml), CoreVocabulary,
│                     NameSimilarity, EntityTypeDef, RelationPredicateDef,
│                     AttributePredicateDef, BrainVocabulary (resolved per brain), VocabularyConfigValidator,
│                     PromptVocabularyRenderer, OutputSchemaRenderer
├── graph             EntityStore, ClaimStore, AliasStore, IngestionStore, SourceRefStore, ConflictStore,
│                     ProjectionService, SchemaInitializer (SCHEMA §8), ProjectionVerifier, internal/CypherStatements
├── resolution        CandidateFinder (alias + fulltext), MentionCandidates, TypeCompatibility, DuplicateSuspicion
├── ingestion         IngestionService (facade), IngestRequest/IngestResult,
│   ├── pipeline      ExtractionStage, CandidateStage, ReconcileStage, ValidationStage, ApplyStage
│   ├── ops           MutationOperation (sealed) + records (§4), EntityTarget, ProposalDto (wire), ProposalParser
│   └── validation    OperationValidator, ValidationIssue, EvidenceChecker, SecretScanner
├── knowledge         KnowledgeService: manual entity/claim CRUD, corrections, merge (no LLM)
├── conflict          ConflictService: list/resolve conflicts, consequence rules (Decision REPLACES → status)
├── retrieval         SearchService, EntityContextService, NeighborhoodService, ContextRenderer
├── llm               LlmGateway (interface), SpringAiLlmGateway, PromptTemplates (versioned resources),
│                     LlmProperties, provider auto-configuration notes (§7)
├── security          ApiKeyService, ApiKeyAuthenticationFilter, SecurityConfig, BrainAccessGuard, Scope, RateLimiter
├── api
│   ├── rest          controllers per feature, DTOs, ProblemDetail error advice, OpenAPI config
│   └── mcp           McpTools (@Tool methods), McpToolErrorMapper
└── admin             ApiKeyAdminService (admin key lifecycle), brain deletion job
```

Rules:

- Controllers and MCP tools contain no business logic. They map DTOs and call one service method each.
- Only the `graph` module contains Cypher. Every store method takes `BrainScope` as its first parameter. There is no store method without a brain parameter, except `BrainStore.listAll`, `ApiKeyStore.*` and `SchemaInitializer`.
- The `llm` module knows nothing about Neo4j, and `graph` knows nothing about the LLM.
- Records are used for all DTOs and operations, and they are immutable.
- Jackson: Spring Boot 4 defaults to **Jackson 3** (`tools.jackson.*` packages). **VERIFY** which Jackson major version Spring AI 2.1 uses internally. Use Boot's auto-configured mapper for our own parsing.

## 3. Ingestion pipeline

```
IngestRequest ─► [0 admit] ─► [1 extract: LLM] ─► [2 candidates: Neo4j read] ─► [3 reconcile: LLM]
             ─► [4 validate: pure Java] ─► [5 apply: one Neo4j write tx] ─► IngestResult
```

**0. Admit (no LLM)**
- Authenticate and authorize: `INGEST` scope on the brain.
- Check request limits (§9).
- Compute `contentHash`.
- Idempotency check (§6).
- Run the secret scan on the content. Secrets are redacted to `[REDACTED]` before the content reaches the LLM. Ingestion is never rejected because a secret was found.

**1. Extract (LLM call 1, prompt `extract-v1`)**
- Input: the brain vocabulary (rendered), the speaker context, the contentType, the source refs (locator, title and summary only), and the content inside a delimited data block.
- Output: `ExtractionResult { summary, mentions[], statements[] }`. Each mention has `localRef, name, type, aliases[], isSpeaker, description, evidence`. Each statement has `subjectRef, predicate, objectRef | value, polarity, statement, evidence, confidence, validFrom, validTo, correctionCue: boolean`.
- No graph data goes in. This stage cannot hallucinate ids.

**2. Candidates (deterministic, read transaction)**

For every mention:
- Exact alias lookup on `norm(name)` and each alias.
- Fulltext top-k (k = 5).
- Type compatibility filter (SCHEMA §6.2).
- For each candidate, include up to 10 ACTIVE claims by recency, and all ACTIVE slot claims (cardinality ONE) for predicates in the extracted statements.

The engine builds a `CandidateSet` and assigns each candidate a short opaque handle (`k1`, `k2` …) mapped to its real id. The LLM sees handles, not UUIDs. Stage 3 output may reference only these handles, and `P0` for the speaker. Even a prompt-injected model therefore cannot name an arbitrary node or a node from another brain.

**3. Reconcile (LLM call 2, prompt `reconcile-v1`)**
- Input: the extraction result, the candidate set (handles, type, name, aliases, description truncated to 300 characters, active claims as `{claimHandle, statement, predicate, object}`), and the operation contract with its JSON schema.
- Output: `Proposal { summary, operations[] }` (§4).

**4. Validate (pure Java, no I/O beyond the immutable candidate set)**
- Validate every operation against the rules in §5.
- Invalid operations are dropped with a `ValidationIssue`. Operations that depend on a dropped `ref` are dropped transitively.
- The result is a `ValidatedPlan`: an ordered list of fully resolved commands with real ids, types and keys computed.

**5. Apply (one write transaction)**
- Execute the plan through the stores, in order: entities, then aliases, then source refs, then claims, then supersessions and retractions, then conflicts, then consequence rules, then mention edges, then the Ingestion record.
- Any exception rolls back everything. The Ingestion is then recorded as FAILED in a separate transaction, with no knowledge written.
- On a uniqueness-constraint violation from a concurrent writer (for example two ingestions creating the same entity), retry **once**: go back to stage 2 with the same proposal re-resolved, without calling the LLM again. Ops that targeted `ref`s now resolve to existing nodes by canonicalKey.

**Modes**
- `dryRun=true` stops after stage 4 and returns the plan. Nothing is persisted, not even the Ingestion record.
- `graphspindle.llm.enabled=false` (or a missing provider) makes ingest return `503 LLM_UNAVAILABLE`. Management and retrieval keep working.

**Single-call mode:** `graphspindle.ingestion.pipeline=single-pass` merges stages 1 and 3 by running candidate lookup on a cheap deterministic pre-extraction (capitalized n-grams plus the alias index) and then calling the LLM once. This is optional and lands after M7. The two-stage pipeline is the default because it is more reliable for small local models.

Synchronous MVP: ingestion runs within the HTTP or MCP request, with `graphspindle.llm.timeout` (default 120 s). A later async mode (`202` plus `GET ingestion`) can reuse the same stages.

## 4. Mutation operation contract (LLM output, stage 3)

The wire format is JSON with an `op` discriminator in snake_case. In Java it becomes `sealed interface MutationOperation permits CreateEntity, UpdateEntity, AddAlias, AssertClaim, SupersedeClaim, RetractClaim, FlagConflict, ProposeMerge, LinkSource`.

### 4.1 Common types

```jsonc
// EntityTarget: exactly one of
{"ref": "e1"}        // local ref of a create_entity in the same proposal: ^[a-z][a-z0-9_]{0,15}$
{"id": "k3"}         // candidate handle from stage 2, or "P0" for the speaker/self
// ClaimTarget
"claimId": "c7"      // claim handle from stage 2 (never a UUID)
```

### 4.2 Operations

| op | Fields (R = required) | Java record |
|---|---|---|
| `create_entity` | `ref` R, `type` R, `name` R (≤200), `description` (≤1000), `aliases` (≤5, each ≤200) | `CreateEntity(String ref, String type, String name, String description, List<String> aliases)` |
| `update_entity` | `target` R, `description` R (≤1000), `reason` (≤300) | `UpdateEntity(EntityTarget target, String description, String reason)` |
| `add_alias` | `target` R, `alias` R (≤200) | `AddAlias(EntityTarget target, String alias)` |
| `assert_claim` | `subject` R, `predicate` R, exactly one of `object` (EntityTarget) or `value` (string), `polarity` (`POSITIVE` default \| `NEGATIVE`), `statement` R (≤500), `evidence` R (≤300), `confidence` R (0–1), `validFrom`, `validTo` (PartialDate), `sources` (indexes into request sources) | `AssertClaim(...)` |
| `supersede_claim` | `claimId` R, `explicitCorrection` R (bool), `reason` R (≤300), `replacement` R (an `assert_claim` body without `op`) | `SupersedeClaim(String claimHandle, boolean explicitCorrection, String reason, AssertClaim replacement)` |
| `retract_claim` | `claimId` R, `reason` R, `evidence` R | `RetractClaim(String claimHandle, String reason, String evidence)` |
| `flag_conflict` | `claimId` R (existing), `incoming` R (an `assert_claim` body), `description` R (≤500) | `FlagConflict(...)`: stores the incoming claim as DISPUTED |
| `propose_merge` | `a` R, `b` R (EntityTargets), `reason` R (≤300) | `ProposeMerge(...)`: creates a DUPLICATE_CANDIDATE conflict |
| `link_source` | `source` R (index), `target` R | `LinkSource(int sourceIndex, EntityTarget target)` |

`CreateRelationship` is deliberately **not** a separate operation. A relationship is an `assert_claim` with a relation predicate (SCHEMA §6.3), so every edge carries provenance. The Java layer may expose `assertRelation(...)` as a convenience for manual APIs.

The top-level envelope is `{"summary": string ≤1000, "operations": [ ≤ maxOpsPerIngestion ]}`.

### 4.3 Parsing approach

Call `ChatClient.prompt().system(...).user(...).call().content()` to get the raw string, then parse it with our own Jackson-based `ProposalParser`:

1. Strip markdown code fences and leading or trailing prose up to the outermost `{…}`.
2. Lenient read into `ProposalDto`, with unknown properties ignored and recorded.
3. Map to sealed records with explicit null checks.

The JSON schema text placed in the prompt is generated by `OutputSchemaRenderer` from the brain vocabulary, with enum lists for `type` and `predicate`.

Why not `.entity(Class)` / `BeanOutputConverter`? Polymorphic sealed hierarchies do not produce reliable schemas across providers. **VERIFY** the current Spring AI 2.1 structured-output capabilities, including native JSON-schema response formats for OpenAI and Ollama `format`. These may be enabled as an optimization through `ChatOptions`, but the parser stays the single source of truth.

**Repair:** if parsing fails, make one repair call (prompt `repair-v1`: "Return only valid JSON matching the schema; previous output: …" with the output truncated to 8 KB). A second failure ends the ingestion as `FAILED` with reason `MALFORMED_MODEL_OUTPUT`, and nothing is written.

## 5. Validation rules (stage 4)

Each rule has a stable code, returned in `IngestResult.rejected[]`.

| Code | Rule |
|---|---|
| `UNKNOWN_OP` | The `op` value is not in the list |
| `UNKNOWN_TYPE` | The type is not enabled in the brain vocabulary |
| `UNKNOWN_PREDICATE` | The predicate is not enabled, or attribute vs relation usage is wrong for it |
| `TYPE_PAIR_NOT_ALLOWED` | The subject or object type is not allowed for the predicate (SCHEMA §6.3/§6.4) |
| `INVALID_VALUE` | Enum, date, URL, number or length check failed, or a value is reserved for the engine (Decision `SUPERSEDED`) |
| `UNKNOWN_HANDLE` | The `id` or `claimId` is not in this ingestion's candidate set, or a `ref` is not defined earlier in the proposal |
| `DUPLICATE_REF` | The same `ref` is defined twice |
| `TYPE_INCOMPATIBLE_MATCH` | A mention resolves to a candidate of an incompatible type |
| `EVIDENCE_NOT_FOUND` | `evidence` is not a substring of the (redacted) content or of a source summary after whitespace and case normalization, is too short to prove anything, or neither the quote nor its sentence mentions the claim's subject, object or value (ADR-021). Exactly what blocks invented facts. |
| `SELF_LOOP` | Subject equals object |
| `LIMIT_EXCEEDED` | More than the allowed operations, entities, aliases or claims per subject |
| `LOW_CONFIDENCE` | Confidence is below the brain `minConfidence` (default 0.5). The op is dropped. |
| `SECRET_DETECTED` | A string field matches the secret patterns |
| `NAME_INVALID` | Normalization produced an empty name, the name starts with `@`, or the name is a pronoun or a stop-word only |
| `PROTECTED_TARGET` | `update_entity` on a `descriptionLocked` entity, or `retract_claim`/`supersede_claim` on a MANUAL claim. Converted into a `PROTECTED_OVERRIDE` conflict rather than silently dropped. |
| `NOT_SLOT_CONFLICT` | `supersede_claim` whose replacement has a different subject or predicate than the claim it supersedes |

Normalizations that are applied silently and reported as `adjusted[]`:

- `create_entity` whose canonicalKey already exists in the candidate set becomes a reference to the existing entity.
- `confidence` is clamped to `maxLlmConfidence` (default 0.95).
- A contradiction in an `assert_claim` (detected against slot claims in the candidate set) is routed by the conflict policy (SCHEMA §9.4).

## 6. Idempotency

- `requestId` is client-supplied (≤100 chars, `[A-Za-z0-9._:-]`). If it is absent, the server generates one, and then only the content-hash rule applies.
- `contentHash = sha256(contentType + "\n" + normalizeWhitespace(content) + "\n" + canonicalJson(sources) + "\n" + speaker)`.
- Behaviour per case:
  - Same `(brainId, requestId)` and same hash: return the stored `IngestResult` (HTTP 200 with `replayed: true`). The LLM is not called.
  - Same `(brainId, requestId)` and a different hash: `409 IDEMPOTENCY_KEY_REUSED`.
  - A new requestId but the same `(brainId, contentHash)` already APPLIED within `dedupWindow` (default 24 h): return the previous result with `duplicateOf`, unless `force=true`.
- The `(brainId, requestId)` uniqueness constraint makes concurrent replays safe. The first insert of a RECEIVED Ingestion wins, and the loser gets `409 IN_PROGRESS`.
- Claim writes are idempotent through `activeKey` (reinforcement), and entity writes through `canonicalKey` MERGE. A stage-5 retry therefore cannot duplicate knowledge.

## 7. LLM provider configuration

- The `llm` module depends only on `ChatModel` / `ChatClient`.
- Provider starters on the classpath: Ollama, OpenAI and Anthropic. **VERIFY** the artifact ids under the Spring AI 2.1 BOM, expected to be `spring-ai-starter-model-ollama`, `spring-ai-starter-model-openai` and `spring-ai-starter-model-anthropic`.
- Selection: **VERIFY** the Spring AI 1.x/2.x property `spring.ai.model.chat=ollama|openai|anthropic|none`, which controls which chat auto-configuration is active. We expose `GRAPHSPINDLE_LLM_PROVIDER`, mapped onto it in `application.yml`.
- OpenAI-compatible servers (LM Studio, vLLM, llama.cpp server, Groq, OpenRouter) use the OpenAI starter with `spring.ai.openai.base-url`, `spring.ai.openai.api-key` (a dummy key for local servers) and `spring.ai.openai.chat.options.model`. **VERIFY** the exact property names and the completions path override for servers that do not serve `/v1/chat/completions` at the default path.
- Provider-neutral options set by us: temperature 0 (or the provider minimum), max output tokens (`graphspindle.llm.max-output-tokens`, default 4096), and timeout.
- Profiles: `ollama` (default local), `openai`, `anthropic`, `openai-compatible`, plus `test`.
- Tests use `FakeChatModel implements ChatModel` (src/test). It returns scripted responses selected by a matcher on the prompt id and on content-hash fixtures, records the calls, and can produce malformed, injected or oversized outputs on demand. There is no network access in tests.
- Data-transfer notice: when a remote provider is active, the startup log and `GET /api/v1/system/info` state that content is sent to `<provider host>`.
- `LlmGateway` records the provider, model and prompt version on the Ingestion, along with token usage if the `ChatResponse` metadata exposes it.

Prompts live in `src/main/resources/prompts/*.st` as versioned files. Changing a prompt means bumping the version id.

## 8. Security

**Authentication**
- `Authorization: Bearer gsk_<keyId>_<secret>` or `X-API-Key: gsk_<keyId>_<secret>`, where the secret is 32 random bytes in base64url (ADR-033).
- The server stores `sha256(secret)` and compares it in constant time.
- Admin bootstrap: `GRAPHSPINDLE_ADMIN_API_KEY` (at least 32 characters), read from the environment; only its hash is kept and it is never logged.
- With no admin key configured and a non-loopback bind address, startup fails.
- `graphspindle.security.enabled=false` is allowed **only** when the bind address is loopback. It logs a warning and is meant for local development.

**Authorization**
- Per-brain permissions `READ` < `WRITE` (ingestion, manual mutations, conflict resolution) < `ADMIN` (config, vocabulary, API keys of that brain, delete), stored as `:ApiKeyGrant` nodes; the global admin is the bootstrap key (create brains, all keys). ADR-033 supersedes the earlier five-scope list.
- `BrainAccessGuard.require(actor, brainId, permission)` is called at the start of every service method that takes a brain (`ServiceGuardTest` enforces it). No grant: 404 `BRAIN_NOT_FOUND` (like a missing brain); too low a permission: 403. It is called in the service, not the controller, so that REST and MCP share it.
- `brainId` always comes from the authenticated request path or tool argument. It never comes from content or LLM output.

**MCP**
- Same filter chain on `/mcp` (Streamable HTTP, verified). Host/Origin allowlist on REST and MCP against DNS rebinding; MCP sessions idle out after 30 min (ADR-034).
- Each tool takes `brainId` as an argument and calls the guard. STDIO transport is not offered in the MVP, because it would bypass HTTP authentication.

**Prompt injection**
- Content is wrapped in a `<<<DATA … DATA>>>` block with a system instruction to treat it as data only.
- The ingestion LLM has **no tools**. `ChatClient` is built without tool callbacks.
- Output is accepted only through the validator. Handles restrict which nodes can be targeted.
- Evidence must be a literal substring of the input.
- Instructions in the content cannot change brainId, scopes, vocabulary or policy, because none of these come from the LLM.
- Retrieved graph text returned to clients is marked `untrusted: true` in DTOs and wrapped in MCP tool results.

**Limits (defaults, configurable)**
- Content ≤ 32,000 characters; ≤ 10 sources; ≤ 60 ops; ≤ 25 new entities; ≤ 5 aliases per entity per ingestion.
- Request body ≤ 1 MiB (`graphspindle.api.max-request-bytes`; 413 `LIMIT_EXCEEDED`, also for streamed bodies). JSON input is strict: unknown properties, duplicate keys and trailing content are 400 (ADR-022).
- Search `limit` ≤ 50; neighbourhood depth ≤ 2 and ≤ 200 nodes.
- Per-key token bucket: ingest 30/min, read 300/min, in memory. **Flagged:** this is single-instance only.

**Logs and data**
- Content and LLM outputs are never logged at INFO. At DEBUG they go through redaction and truncation (200 characters).
- API keys are never logged. Neo4j credentials come from the environment.
- `.env.example` is sanitized.
- Neo4j stays bound to localhost in compose. GraphSpindle never exposes Neo4j or Cypher.

## 9. Retrieval

All retrieval is deterministic and needs no LLM.

- `search(brain, query, types?, limit)`:
  - Exact alias hits come first (score 1.0), then fulltext over entities, then claim statements, then source summaries.
  - Results are merged by entity, and each hit carries a short `matchedBy` reason.
- `entityContext(brain, entityId, maxClaims=30)`:
  - The entity, its aliases, its ACTIVE claims (statement, predicate, object, confidence, evidence count, and up to 3 source refs), and its DISPUTED claims together with their open conflicts.
  - `MERGED` ids are followed to the survivor.
  - Rendered as JSON and also as a compact text block (`ContextRenderer`) for agents.
- Plain node view (ADR-024, ADR-026), the default entity read: node fields, current properties (the `attr_` projection without the prefix) and current relationships (ACTIVE positive relation claims, both directions); claims only on request.
- `asOf(brain, entityId, instant)` reconstructs properties and relationships as the brain believed them at that record time, and `history(brain, entityId)` lists property and relationship changes (old → new, with claim, origin, ingestion, principal, reason and sources) in record-time order. Both replay the claim timeline (`GraphReader.claimTimeline`).
- `neighbors(brain, entityId, depth 1–2, predicates?, limit)` traverses projection edges only. The query is restricted to edge types from the vocabulary, and every node in the pattern is matched with `brainId`.
- `claimHistory(brain, claimId | subjectId+predicate)` returns the SUPERSEDES chain ordered by `recordedAt`.
- An LLM-generated answer ("ask the brain") is out of MVP scope. If it is added later, it must be built on `entityContext` / `search` results with no write capability.

## 10. REST API (`/api/v1`, JSON, errors as RFC 9457 `ProblemDetail`)

| Method & path | Scope | Service method |
|---|---|---|
| `GET /system/info` | any authenticated | version, active LLM provider/host, schema version |
| `POST /brains` | ADMIN | `BrainService.create` (creates the self entity) |
| `GET /brains` | any (filtered to the key's brains) | `BrainService.list` |
| `GET /brains/{brainId}` | BRAIN_READ | `BrainService.get` |
| `PATCH /brains/{brainId}` | BRAIN_ADMIN | `BrainService.updateConfig` (If-Match: configVersion) |
| `DELETE /brains/{brainId}?confirm={brainId}` | ADMIN | `BrainService.delete` (batched detach-delete) |
| `GET /brains/{brainId}/vocabulary` | BRAIN_READ | `VocabularyRegistry.forBrain` |
| `PUT /brains/{brainId}/vocabulary` | BRAIN_ADMIN | `BrainService.updateVocabulary` |
| `GET /node-types`, `GET /node-types/{type}` | any authenticated | `VocabularyTypeService.coreNodeTypes/coreNodeType` (YAML core profile, read-only) |
| `GET /relationship-types`, `GET /relationship-types/{name}` | any authenticated | `VocabularyTypeService.coreRelationshipTypes/coreRelationshipType` |
| `GET /brains/{brainId}/node-types` / `/{type}` | BRAIN_READ | `VocabularyTypeService.nodeTypes/nodeType` |
| `POST /brains/{brainId}/node-types` | BRAIN_ADMIN | `VocabularyTypeService.createNodeType` (custom; guardrails, ADR-025) |
| `PUT /brains/{brainId}/node-types/{type}` | BRAIN_ADMIN | `VocabularyTypeService.updateNodeType` (custom fully; core: `enabled` + extra properties) |
| `DELETE /brains/{brainId}/node-types/{type}` | BRAIN_ADMIN | `VocabularyTypeService.deleteNodeType` (custom only, 409 in use) |
| `GET /brains/{brainId}/relationship-types` / `/{name}` | BRAIN_READ | `VocabularyTypeService.relationshipTypes/relationshipType` |
| `POST /brains/{brainId}/relationship-types` | BRAIN_ADMIN | `VocabularyTypeService.createRelationshipType` |
| `PUT /brains/{brainId}/relationship-types/{name}` | BRAIN_ADMIN | `VocabularyTypeService.updateRelationshipType` (custom fully; core: `enabled`) |
| `DELETE /brains/{brainId}/relationship-types/{name}` | BRAIN_ADMIN | `VocabularyTypeService.deleteRelationshipType` (custom only, 409 in use) |
| `POST /brains/{brainId}/ingestions?dryRun=&force=` | INGEST | `IngestionService.ingest` |
| `GET /brains/{brainId}/ingestions` / `/{id}` | BRAIN_READ | `IngestionService.list/get` |
| `GET /brains/{brainId}/search?q=&types=&limit=` | BRAIN_READ | `SearchService.search` |
| `GET /brains/{brainId}/entities?type=&status=&cursor=&limit=` | BRAIN_READ | `KnowledgeService.listEntities` |
| `POST /brains/{brainId}/entities` | BRAIN_WRITE | `KnowledgeService.createEntity` (origin MANUAL) |
| `GET /brains/{brainId}/entities/{id}?include=claims&asOf=` | BRAIN_READ | `EntityReadService.read`: plain view (`NodeViewService.current`, or `.asOf` for a record-time point); `include=claims` adds `EntityContextService.context` (ADR-026) |
| `GET /brains/{brainId}/entities/{id}/history?limit=` | BRAIN_READ | `NodeViewService.history` (property and relationship changes, ADR-026) |
| `PATCH /brains/{brainId}/entities/{id}` | BRAIN_WRITE | `KnowledgeService.updateEntity` (name, description lock) |
| `DELETE /brains/{brainId}/entities/{id}` | BRAIN_WRITE | `KnowledgeService.archiveEntity` (soft) |
| `GET /brains/{brainId}/entities/{id}/aliases` | BRAIN_READ | `KnowledgeService.aliases` |
| `POST /brains/{brainId}/entities/{id}/aliases` | BRAIN_WRITE | `KnowledgeService.addAlias` |
| `GET /brains/{brainId}/entities/{id}/neighbors?depth=&predicates=` | BRAIN_READ | `NeighborhoodService.neighbors` |
| `POST /brains/{brainId}/entities/merge` | BRAIN_WRITE | `KnowledgeService.mergeEntities` |
| `GET /brains/{brainId}/claims?subjectId=&predicate=&status=` | BRAIN_READ | `KnowledgeService.listClaims` |
| `POST /brains/{brainId}/claims` | BRAIN_WRITE | `KnowledgeService.assertClaim` (MANUAL) |
| `GET /brains/{brainId}/claims/{id}` / `/history` | BRAIN_READ | `KnowledgeService.getClaim/claimHistory` |
| `POST /brains/{brainId}/claims/{id}/supersede` | BRAIN_WRITE | `KnowledgeService.supersedeClaim` |
| `POST /brains/{brainId}/claims/{id}/retract` | BRAIN_WRITE | `KnowledgeService.retractClaim` |
| `GET /brains/{brainId}/conflicts?status=` / `/{id}` | BRAIN_READ | `ConflictService.list/get` |
| `POST /brains/{brainId}/conflicts/{id}/resolve` | BRAIN_WRITE | `ConflictService.resolve` |
| `GET /brains/{brainId}/sources` / `/{id}` | BRAIN_READ | `KnowledgeService.listSources/getSource` |
| `POST /admin/api-keys`, `GET /admin/api-keys`, `DELETE /admin/api-keys/{keyId}` | ADMIN (or BRAIN_ADMIN for keys limited to its brains) | `ApiKeyAdminService` |
| `GET /admin/brains/{brainId}/projection-check` | BRAIN_ADMIN | `ProjectionVerifier` |

Implemented in M5 (ADR-023): every row above except ingestions, api-keys and `GET /system/info`; ingestions in M6 (ADR-027, ADR-028: `POST` plus `GET /{id}` and `GET ?requestId=`; listing all ingestions is not implemented); the node-type and relationship-type rows were added with ADR-025 (per-brain responses carry `configVersion` as ETag and writes accept `If-Match`; `graphspindle.vocabulary.api-mutable=false` makes the writes 403). Lists use keyset pagination (`cursor` = the `next` value of the previous page, `limit` ≤ 200, default 50) and return `{items, next}`. `GET /entities/{id}` returns the plain view (node fields, `properties` without the `attr_` prefix, `relationships` with direction, aliases) and accepts `include=claims` (adds `entity`, `claims`, `disputed`, `openConflictIds`, `describedBy`, `text`), `asOf` (ISO date = end of that day UTC, or date-time) and `maxClaims` (≤ 100, default 30); `/history` accepts `limit` (≤ 1000, default 200); `/neighbors` accepts `limit` (≤ 200 nodes, default 50); `/search` takes `types` as a comma-separated list. Until M9 every request acts as the principal `admin`.

Swagger UI is at `/swagger-ui.html` and OpenAPI at `/v3/api-docs`. Both require authentication unless security is disabled on loopback. Actuator `health` is unauthenticated and returns status only, without details.

## 11. MCP tools (same services, same guard)

Implemented in M8 (ADR-032) as `@McpTool` methods on `api.mcp.McpTools` (Spring AI annotation scanner), served over Streamable HTTP at `/mcp` on the REST server. Every tool takes `brainId` explicitly (except `list_brains`), resolves its `Actor` from the MCP transport context (`api.mcp.McpCaller`), and makes one call to the service its REST endpoint calls. Results are compact JSON `{"untrusted":true,"notice":…,"result":<the REST body>}`; errors are tool errors (`isError`) `{"error":{"code","status","detail",…}}` with the REST problem-detail values. A FAILED ingestion is a tool error with its error code plus the result.

| Tool | Args | Delegates to (REST twin) |
|---|---|---|
| `list_brains` | none | `BrainService.list` (`GET /brains`) |
| `get_brain` | brainId | `BrainService.get` |
| `list_node_types` / `list_relationship_types` | brainId | `VocabularyTypeService.nodeTypes/relationshipTypes` (read-only) |
| `remember` | brainId, content, contentType?, requestId?, sources?, speaker?, occurredAt?, dryRun? | `IngestionService.ingest` |
| `get_ingestion` | brainId, ingestionId? \| requestId? | `IngestionService.get/getByRequestId` |
| `search_knowledge` | brainId, query, types?, limit? | `SearchService.search` |
| `list_entities` | brainId, type?, status?, cursor?, limit? | `KnowledgeService.listEntities` |
| `get_entity` | brainId, entityId, includeClaims?, asOf?, maxClaims? | `EntityReadService.read` (plain view; claims add the context text) |
| `get_entity_history` | brainId, entityId, limit? | `NodeViewService.history` |
| `get_neighbors` | brainId, entityId, depth?, predicates?, limit? | `NeighborhoodService.neighbors` |
| `list_claims` | brainId, subjectId?, objectId?, predicate?, status?, cursor?, limit? | `KnowledgeService.listClaims` |
| `get_claim` / `get_claim_history` | brainId, claimId | `KnowledgeService.getClaim/claimHistory` |
| `assert_claim` | brainId, subjectId, predicate, objectId? \| value?, polarity?, statement?, validFrom?, validTo? | `KnowledgeService.assertClaim` (MANUAL) |
| `supersede_claim` | brainId, claimId, reason, objectId? \| value?, polarity?, statement?, validFrom?, validTo? | `KnowledgeService.supersedeClaim` |
| `retract_claim` | brainId, claimId, reason | `KnowledgeService.retractClaim` |
| `list_conflicts` / `get_conflict` | brainId, status?, kind?, cursor?, limit? / conflictId | `ConflictService.list/get` |
| `resolve_conflict` | brainId, conflictId, resolution, note?, survivorId?, validFrom?, validTo? | `ConflictService.resolve` |

`get_vocabulary` and `correct_claim` of the original plan became the read-only type tools and separate `supersede_claim`/`retract_claim` (clearer argument schemas).

Admin operations (brain create/delete, keys, vocabulary edits) are REST-only in the MVP, which reduces the attack surface for agents. Tool errors map to MCP error results that carry the same error codes as REST ProblemDetails.

Parity: a shared contract test suite (M8) runs each scenario through REST and MCP and asserts equal results and equal authorization failures.

## 12. Transactions and consistency

- `@Transactional` with the Neo4j transaction manager auto-configured by Spring Data Neo4j. Stage 5 runs in one write transaction, and reads in stage 2 use a read transaction.
- Projection maintenance happens inside the same transaction as the claim change (SCHEMA I3).
- Merge and conflict resolution are single transactions.
- Every brain-scoped write transaction first takes the Brain node's write lock and re-checks `status = ACTIVE` (ADR-022), so writes to one brain are serialized and none can commit into a brain that is being deleted.
- Brain deletion runs in batches of 10,000 (`CALL { … } IN TRANSACTIONS`). The brain is first set to `DELETING` (under the Brain lock, so it waits for in-flight writes), which blocks all other operations with 409. The Brain node is deleted last, once a re-check finds no other node of the brain.

## 13. Error codes (shared by REST and MCP)

| Code | HTTP | Meaning |
|---|---|---|
| `UNAUTHENTICATED` | 401 | Missing or invalid credentials |
| `FORBIDDEN` | 403 | Wrong scope or brain |
| `BRAIN_NOT_FOUND` | 404 | Unknown brain, also returned for brains the key cannot see, to prevent enumeration |
| `NOT_FOUND` | 404 | Entity, claim, conflict or other resource not found |
| `VALIDATION_FAILED` | 400 | Request validation failed |
| `LIMIT_EXCEEDED` | 413 / 400 | A size or count limit was exceeded |
| `RATE_LIMITED` | 429 | Per-key rate limit hit |
| `IDEMPOTENCY_KEY_REUSED` / `IN_PROGRESS` / `CONFLICT_STATE` | 409 | Idempotency or state conflict |
| `LLM_UNAVAILABLE` | 503 | No LLM configured or provider unreachable |
| `MALFORMED_MODEL_OUTPUT` | 502 | Returned in the result with status FAILED |
| `INTERNAL` | 500 | Unexpected error |
