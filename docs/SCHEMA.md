# GraphSpindle graph schema

Status: design, version `schema-v1`. Developers code from this file. Text marked **VERIFY** must be checked against the official documentation for the pinned versions before anyone relies on it.

Contents

1. Requirements the schema must satisfy
2. Candidate models A–E
3. Comparison and critique
4. Iterations towards the hybrid
5. Final model (normative)
6. Controlled vocabulary (normative)
7. Identifiers and normalization (normative)
8. Constraints and indexes (normative Cypher)
9. Write semantics: claims, projection, conflicts, merges (normative)
10. Worked examples
11. Vocabulary extensibility (normative)
12. Open questions and things to verify

---

## 1. Requirements the schema must satisfy

Taken from README.md and AGENTS.md:

| ID | Requirement |
|----|-------------|
| R1 | **Entity resolution.** A later message adds details to the same entity and does not create a duplicate. |
| R2 | **Provenance.** Every piece of knowledge can be traced to the submission (and optional source reference) it came from, along with who submitted it and when. |
| R3 | **Contradictions and corrections.** Contradictory facts are never silently merged. User or manual knowledge is never silently overwritten. Corrections are supported, and history is kept. |
| R4 | **Temporal validity.** "Was true then, is not now" must be expressible. |
| R5 | **Source references.** Files and URLs are stored as a reference plus a summary. They are never fetched and their raw contents are not stored by default. |
| R6 | **LLM extraction is feasible.** A mid-size local model (for example an 8–14B model on Ollama) must be able to produce valid output reliably. |
| R7 | **Query ergonomics.** Common questions ("what does project X use?") map to short Cypher queries. Clients never write Cypher. |
| R8 | **Controlled vocabulary.** Labels, relationship types and properties come from a bounded list that code can enforce. The LLM cannot invent any of them. |
| R9 | **Multi-brain isolation.** Every node and edge is brain-scoped, uniqueness is per brain, and no operation can cross brains. |
| R10 | **Meaningful storage.** Durable knowledge is stored, not transcripts. |
| R11 | **Implementable on Neo4j Community / neo4j-harness.** Node-key, property-existence and property-type constraints are Enterprise-only, so the design must not depend on them. Composite property *uniqueness* constraints are available in Community. **VERIFY** against the Neo4j 2026.x constraint docs. |

---

## 2. Candidate models

Each candidate is described with enough detail to judge it. Every candidate puts `brainId` on all nodes and edges, so isolation (R9) is discussed only where the models differ.

### Model A: flat typed-entity graph

**Shape.** One node label per kind of thing, with direct typed relationships and facts stored as node properties.

- Labels: `Person, Agent, Organization, Project, Technology, Concept, Decision, Event, Task, Goal, Document, Place, Standard`.
- Relationships: `WORKS_ON, USES, WRITTEN_IN, DEPENDS_ON, PART_OF, DECIDED, CHOOSES, LICENSED_UNDER, ASSIGNED_TO, LOCATED_IN, KNOWS, RELATED_TO …`
- Properties: `name`, `description`, `aliases: [String]`, plus type-specific properties such as `status` and `dueDate`.

**Provenance.** At best, a `sources: [ingestionId]` list on nodes and edges. It cannot express which source supports which fact on a node.
**Duplicates.** Unique `(brainId, type, normalizedName)`. Aliases sit in a list property. Exact lookup on list membership is weak in Neo4j indexes (**VERIFY**), so alias lookup falls back to scans or fulltext.
**Contradictions and corrections.** Contradictions are overwritten. A correction destroys the old value, and history needs a separate audit log.
**Temporal validity.** `since` and `until` properties on edges are possible. Node properties have no history.
**Source references.** `Document` nodes with `url` and `summary`.
**LLM difficulty.** Lowest. The model emits entity triples.
**Query ergonomics.** Best: `(p:Project)-[:USES]->(t)`.
**Verdict.** Easy to build, but it fails R2, R3 and R4, which are explicit product requirements.

### Model B: entity + reified claims (assertion graph)

**Shape.** Entities hold only identity. Every fact is a `Claim` node.

- `(:Claim {predicate, status, confidence, validFrom, validTo, statement})-[:SUBJECT]->(:Entity)`
- `(:Claim)-[:OBJECT]->(:Entity)`, or a literal `value` on the claim
- `(:Claim)-[:EVIDENCED_BY {quote}]->(:Ingestion)`
- `(:Claim)-[:SUPERSEDES]->(:Claim)`

**Provenance.** Exact, per fact, with multiple pieces of evidence per claim.
**Duplicates.** Same as A for entities. Claims are deduplicated by a `(subject, predicate, object)` key.
**Contradictions and corrections.** First-class. Status changes and supersession edges keep the full history.
**Temporal validity.** Bitemporal: valid time (`validFrom`/`validTo`) and transaction time (`recordedAt`/`supersededAt`).
**Source references.** `SourceRef` nodes cited by claims.
**LLM difficulty.** Medium. The output is still triples, but it needs a predicate, evidence and confidence.
**Query ergonomics.** Poor. "What does X use" becomes `(x)<-[:SUBJECT]-(c:Claim {predicate:'USES', status:'ACTIVE'})-[:OBJECT]->(t)`, so every traversal doubles in length and path queries get painful.
**Verdict.** Strongest on correctness, weak for retrieval.

### Model C: PKM / Zettelkasten

**Shape.** `Note` (an atomic idea in free text), `Topic`, `Reference` (a file or URL), and `Tag`. Edges: `(:Note)-[:ABOUT]->(:Topic)`, `(:Note)-[:LINKS_TO]->(:Note)`, `(:Note)-[:CITES]->(:Reference)`, `(:Topic)-[:BROADER]->(:Topic)`.

**Provenance.** Notes record the ingestion they came from.
**Duplicates.** Topics are deduplicated by name. Notes are deduplicated only by fuzzy text similarity, which is unreliable without embeddings.
**Contradictions and corrections.** Implicit. Two notes can disagree and nothing detects it.
**Temporal validity.** Only note creation time.
**Source references.** The best fit of all the models: `Reference {locator, summary}`.
**LLM difficulty.** Lowest of all. It is summarization.
**Query ergonomics.** Good for "notes about X". Useless for structured questions such as "which license did we choose?".
**Verdict.** Close to dumping text (R10). No structured facts and no contradiction handling. The `Reference` + summary idea and the per-submission summary are worth keeping.

### Model D: ontology-aligned (schema.org + PROV-O)

**Shape.** schema.org classes (`Person, Organization, SoftwareSourceCode, SoftwareApplication, CreativeWork, Event, Place, DefinedTerm, ChooseAction …`) with schema.org properties (`author`, `programmingLanguage`, `license`, `memberOf`, `about`). PROV-O provides provenance: `prov:Entity`, `prov:Activity` (an ingestion), `prov:Agent`, `wasGeneratedBy`, `wasAttributedTo`, `wasDerivedFrom`, `generatedAtTime`, `invalidatedAtTime`.

**Provenance.** Rigorous: activity, agent and entity are first-class.
**Duplicates.** Can use `sameAs` and external identifiers such as Wikidata QIDs, but that implies external lookups, which are out of scope.
**Contradictions and corrections.** PROV can express derivation and invalidation, and adding reification gets you to model B. Plain schema.org has no contradiction handling.
**Temporal validity.** `prov:invalidatedAtTime`, `schema:startDate`/`endDate`.
**Source references.** `CreativeWork {url, abstract}`.
**LLM difficulty.** Highest. The vocabulary is large and subtle (`SoftwareSourceCode` vs `SoftwareApplication`, `ChooseAction`), and small models misuse it.
**Query ergonomics.** Verbose names, and multi-valued property semantics are inconsistent.
**Verdict.** Valuable as a **naming reference** and as an export mapping. Too big and too subtle to be the controlled vocabulary for LLM extraction.

### Model E: temporal episodic graph (facts as edges, Graphiti/Zep style)

**Shape.** `Episode` nodes (one per submission) and `Entity` nodes. Each fact is a typed **edge** between entities that carries `fact` text, `validAt`, `invalidAt`, `createdAt`, `expiredAt` and `episodes: [id]`. `(:Episode)-[:MENTIONS]->(:Entity)`. Literal attributes live on nodes.

**Provenance.** Per fact, through a list of episode ids on the edge. This is denormalized: there is no evidence quote per episode and no way to traverse from a fact to its sources.
**Duplicates.** Entity resolution against existing nodes, with an LLM or embeddings.
**Contradictions and corrections.** A new contradicting edge sets `invalidAt` on the old edge, so the history survives as invalidated edges. The default is "latest wins". That conflicts with R3 ("do not silently ... overwrite user knowledge") unless a dispute state is added. An edge cannot be the target of a conflict-resolution node, because Neo4j does not allow edges to point at edges.
**Temporal validity.** Excellent (bitemporal on edges).
**Source references.** Episodes only. Nothing separates "a file mentioned" from "the submission itself".
**LLM difficulty.** Medium.
**Query ergonomics.** Good for current facts if every query filters `invalidAt IS NULL`. Facts are edges, so a fact cannot have multiple evidence edges or link to a `Conflict` node.
**Verdict.** Close to what we want. Its weakness is that an edge cannot be referenced: no evidence edges, conflicts, corrections or citations can point at it.

---

## 3. Comparison and critique

Scores run 1 (poor) to 5 (excellent) against the requirements in §1. "Impl" means implementation simplicity (higher is simpler).

| Model | R1 dedup | R2 prov | R3 contra/corr | R4 temporal | R5 sources | R6 LLM ease | R7 query | R8 vocab | R10 meaningful | R11 Community | Impl | **Total** |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A flat typed | 3 | 1 | 1 | 2 | 3 | 5 | 5 | 5 | 4 | 5 | 5 | **39** |
| B reified claims | 3 | 5 | 5 | 5 | 4 | 3 | 2 | 5 | 4 | 5 | 3 | **44** |
| C PKM notes | 2 | 3 | 1 | 1 | 5 | 5 | 2 | 3 | 2 | 5 | 4 | **33** |
| D schema.org+PROV | 3 | 5 | 3 | 4 | 4 | 1 | 2 | 3 | 4 | 5 | 2 | **36** |
| E temporal edges | 4 | 3 | 3 | 5 | 2 | 3 | 4 | 4 | 4 | 5 | 3 | **40** |
| **F hybrid (final, §5)** | 4 | 5 | 5 | 5 | 5 | 4 | 5 | 5 | 4 | 5 | 2 | **49** |

**Critique summary**

- Only B covers R2, R3 and R4 fully. Its weak point is R7 (query ergonomics).
- A and E have the query shape we want. In both, a fact is either a property or an edge, so it cannot be the target of evidence, conflicts or supersession.
- C contributes `Reference` + summary and the "summary of the submission" idea, and nothing else.
- D contributes naming (`Person`, `Organization`, `Place`, `Event`) and the PROV split between a submission as an activity, a source as a referenced entity, and a principal as an agent. Model F keeps that split as `Ingestion`, `SourceRef` and `principal`.
- An LLM-friendly vocabulary is about **15 entity types and about 30 predicates**, each with a one-line description and examples. Larger vocabularies (D) measurably degrade small-model output. Below about 8 types, everything collapses into `Concept`.

---

## 4. Iterations towards the hybrid

**Iteration 1: B as is.** Claims are the only representation of facts.
*Critique:* retrieval and neighbourhood queries need two hops per fact. MCP clients mostly ask "tell me about X", so this cost is paid on every read.

**Iteration 2: B + projection edges.** Every **active** relation claim is also materialized as a direct typed edge `(s)-[:USES {claimId}]->(o)`, which gives model A's query shape. Claims stay the source of truth. Projection edges are a derived index that is rebuilt from claims inside the same transaction.
*Critique:* attributes such as `status` and `category` were still plain node properties, so a change of project status lost provenance and history.

**Iteration 3: attributes become claims too.** A predicate is either a **relation predicate** (object is an entity, projected as an edge) or an **attribute predicate** (object is a typed literal, projected as the node property `attr_<name>`). One mechanism then covers provenance, supersession and conflicts.
*Critique:* aliases in a list property cannot be looked up exactly per brain through a uniqueness-backed index, and ambiguous aliases ("Spring") legitimately belong to several entities.

**Iteration 4: alias nodes.** `(:Entity)-[:HAS_ALIAS]->(:Alias {brainId, normalized})`, unique on `(brainId, normalized)`. One alias can point to several entities, and the entity's own normalized name is also an alias, so the lookup is a single indexed path.
*Critique:* contradictory claims about single-valued predicates (two licenses) must never both be "the current truth", and enforcing that only in Java can be broken by concurrent ingestions.

**Iteration 5: database-enforced claim slots.** Two helper properties exist only while a claim is `ACTIVE`:

- `activeKey`: at most one active claim per `(subject, predicate, object/value, polarity)`. It deduplicates repeated statements.
- `slotKey`: only for predicates with cardinality `ONE`. At most one active claim per `(subject, predicate)`.

Uniqueness constraints ignore nodes that lack the property, so Community-edition uniqueness constraints enforce these invariants even under concurrency. A contradicting claim that is not an explicit correction is stored as `DISPUTED` without those keys, and a `Conflict` node is opened.
*Critique:* the ingestion itself (the episode) and the referenced file are different things, as models C and D showed.

**Iteration 6 (final, model F).** Split provenance into `Ingestion` (the submission event: who, when, content hash, summary, but no raw text by default) and `SourceRef` (an external file, URL or message referenced by locator plus summary and never fetched). Add `Conflict` nodes for contradictions and duplicate candidates. Keep Decision, Task and Goal as entity types rather than special structures, because LLMs extract them well as nouns.

---

## 5. Final model F (normative)

### 5.1 Layers

```
 Knowledge layer      (:Entity:<Type>)  ──[:<RELATION_PREDICATE> {claimId}]──►  (:Entity:<Type>)     ← projection, derived
                            ▲    │ attr_* properties (projection, derived)
           [:SUBJECT]       │    └─[:HAS_ALIAS]─► (:Alias)
                            │
 Assertion layer      (:Claim) ──[:OBJECT]──► (:Entity)        (:Claim)-[:SUPERSEDES]->(:Claim)
                            │                                    (:Conflict)-[:INVOLVES]->(:Claim|:Entity)
                            │
 Provenance layer     [:EVIDENCED_BY {quote}] ─► (:Ingestion) ◄─[:SUBMITTED_IN]─ (:SourceRef)
                      [:CITES] ─────────────────────────────────────────────────► (:SourceRef)-[:DESCRIBES]->(:Entity)
 Administration       (:Brain)   (:ApiKey)
```

**Invariants**

- **I1.** Every node except `ApiKey` has a `brainId`. `Brain` has `brainId == id`. Every relationship except those of `ApiKey` has a `brainId`, equal to the `brainId` of both endpoints.
- **I2.** A relationship never connects nodes with different `brainId`s.
- **I3.** Claims are the source of truth. Projection edges and `attr_*` properties are derived. They exist **iff** a claim with `status = ACTIVE` and `polarity = POSITIVE` exists, and they are created and removed in the same transaction as the claim status change.
- **I4.** Raw ingestion content is not stored unless the brain enables `retainRawContent`. Even then it is stored only on `Ingestion.rawContent`, capped in length.
- **I5.** Nothing in the graph is created from LLM output without passing the validator (ARCHITECTURE.md §5). Labels, relationship types and property names always come from the vocabulary.
- **I6.** Nothing is hard-deleted by ingestion. Corrections change status. Hard deletion exists only in admin APIs (delete brain, purge archived entity).

### 5.2 Node labels

| Label | Role | Brain-scoped |
|---|---|---|
| `Entity` + exactly one type label (e.g. `Project`) | A durable thing in the brain | yes |
| `Alias` | A normalized name string used for lookup | yes |
| `Claim` | A reified assertion (relation or attribute) | yes |
| `Ingestion` | One submission (episode) and its processing outcome | yes |
| `SourceRef` | A reference to an external file, URL or message, with a summary | yes |
| `Conflict` | An open or resolved contradiction or duplicate candidate | yes |
| `Brain` | Brain metadata and configuration | yes (`brainId = id`) |
| `ApiKey` | An authentication credential (hashed) | no (system) |

Reserved labels that the vocabulary must never reuse: the eight labels above, plus `System`, `Internal`, and anything starting with `_`.

### 5.3 System relationship types

| Type | From → To | Properties | Cardinality |
|---|---|---|---|
| `HAS_ALIAS` | Entity → Alias | `brainId`, `origin` (`LLM`/`MANUAL`/`SYSTEM`), `createdAt` | many to many |
| `SUBJECT` | Claim → Entity | `brainId` | exactly 1 per claim |
| `OBJECT` | Claim → Entity | `brainId` | exactly 1 for relation claims, 0 for attribute claims |
| `EVIDENCED_BY` | Claim → Ingestion | `brainId`, `quote` (≤ 300), `confidence`, `recordedAt` | ≥ 1 for `origin = LLM`; 0+ for `MANUAL` |
| `CITES` | Claim → SourceRef | `brainId` | 0+ |
| `SUPERSEDES` | Claim → Claim | `brainId`, `reason` (≤ 300), `at` | 0..1 outgoing |
| `MENTIONED_IN` | Entity → Ingestion | `brainId`, `surfaceForm` (≤ 200) | 0+ |
| `SUBMITTED_IN` | SourceRef → Ingestion | `brainId` | 1+ |
| `DESCRIBES` | SourceRef → Entity | `brainId`, `claimless: true` | 0+ |
| `MERGED_INTO` | Entity → Entity | `brainId`, `at`, `by` | 0..1 outgoing |
| `INVOLVES` | Conflict → Claim or Entity | `brainId`, `role` (`EXISTING`/`INCOMING`/`CANDIDATE_A`/`CANDIDATE_B`) | 2+ |
| `RAISED_BY` | Conflict → Ingestion | `brainId` | 0..1 |

Notes on `SUPERSEDES` and `MERGED_INTO` direction: they point from new to old, so `(new)-[:SUPERSEDES]->(old)`. For `MERGED_INTO` the direction is `(loser)-[:MERGED_INTO]->(survivor)`.

`DESCRIBES` is the one link that is not backed by a claim: "this file is about that entity" is provenance, not world knowledge.

Projection edge types are the relation predicates in §6.3. Their properties are `brainId`, `claimId`, `confidence`, `validFrom` (nullable), and `recordedAt`.

### 5.4 Properties by label

Types: `String(n)` is a string with at most n Unicode code points. `Enum` values are upper-snake. `DateTime` is a Neo4j `DATETIME` in UTC. `PartialDate` is a string `YYYY`, `YYYY-MM` or `YYYY-MM-DD`, stored alongside a sortable `…Sort` string padded to `YYYY-MM-DD`, using `-01` for missing parts. R means required (enforced in Java; there are no existence constraints in Community). O means optional.

#### Entity

| Property | Type | R/O | Notes |
|---|---|---|---|
| `brainId` | String(63) | R | |
| `id` | String(36) | R | UUIDv7, engine-generated |
| `type` | String(40) | R | Equals the type label |
| `name` | String(200) | R | Display name, as first seen or as manually set |
| `normalizedName` | String(200) | R | See §7 |
| `canonicalKey` | String(250) | R | `<type>:<normalizedName>[~n]`, see §7 |
| `description` | String(1000) | O | Short, engine-maintained summary of what the entity is |
| `descriptionLocked` | Boolean | O (default false) | `true` once a MANUAL edit sets the description. The LLM can then no longer change it. |
| `status` | Enum `ACTIVE` \| `MERGED` \| `ARCHIVED` | R | |
| `origin` | Enum `LLM` \| `MANUAL` \| `SYSTEM` | R | |
| `createdAt`, `updatedAt` | DateTime | R | |
| `createdByIngestionId` | String(36) | O | |
| `mentionCount` | Integer | R | Incremented once per ingestion that mentions the entity |
| `lastMentionedAt` | DateTime | O | |
| `mergedIntoId` | String(36) | O | Set when `status = MERGED` |
| `attr_<name>` | per attribute predicate | O | Projection of an active attribute claim (§6.4) |

#### Alias

| Property | Type | R/O |
|---|---|---|
| `brainId` | String(63) | R |
| `normalized` | String(200) | R |
| `display` | String(200) | R (first surface form seen) |
| `createdAt` | DateTime | R |

#### Claim

| Property | Type | R/O | Notes |
|---|---|---|---|
| `brainId`, `id` | String | R | `id` is a UUIDv7 |
| `kind` | Enum `RELATION` \| `ATTRIBUTE` | R | |
| `predicate` | String(40) | R | From the vocabulary |
| `subjectId` | String(36) | R | Denormalized copy of the `SUBJECT` target |
| `objectId` | String(36) | R if RELATION | Denormalized copy of the `OBJECT` target |
| `valueType` | Enum `STRING` \| `ENUM` \| `PARTIAL_DATE` \| `BOOLEAN` \| `NUMBER` \| `URL` | R if ATTRIBUTE | |
| `value` | String(500) | R if ATTRIBUTE | Canonical string form |
| `valueNorm` | String(500) | R if ATTRIBUTE | Normalized for keys (§7) |
| `polarity` | Enum `POSITIVE` \| `NEGATIVE` | R | `NEGATIVE` = "X does **not** use Y". It is never projected. |
| `statement` | String(500) | R | A self-contained natural-language sentence, used for retrieval and display |
| `status` | Enum `ACTIVE` \| `DISPUTED` \| `SUPERSEDED` \| `RETRACTED` | R | |
| `confidence` | Float 0.0–1.0 | R | min(LLM confidence, brain `maxLlmConfidence`). MANUAL claims get 1.0. |
| `origin` | Enum `LLM` \| `MANUAL` \| `SYSTEM` | R | |
| `validFrom`, `validTo` | PartialDate | O | World time, only when stated or clearly implied |
| `validFromSort`, `validToSort` | String(10) | O | |
| `recordedAt` | DateTime | R | Transaction time |
| `statusChangedAt` | DateTime | R | |
| `statusReason` | String(300) | O | |
| `createdByIngestionId` | String(36) | O | |
| `createdByPrincipal` | String(100) | R | API key id or `admin` |
| `activeKey` | String(64) | present iff ACTIVE | §7.4 |
| `slotKey` | String(64) | present iff ACTIVE, cardinality ONE and POSITIVE | §7.4 |
| `evidenceCount` | Integer | R | |

#### Ingestion

| Property | Type | R/O | Notes |
|---|---|---|---|
| `brainId`, `id` | String | R | |
| `requestId` | String(100) | R | Client idempotency key; generated if the client omits it |
| `contentHash` | String(64) | R | SHA-256 hex of the canonical payload (ARCHITECTURE §6) |
| `contentType` | Enum `CONVERSATION` \| `NOTE` \| `OBSERVATION` \| `DOCUMENT_SUMMARY` \| `OTHER` | R | |
| `contentLength` | Integer | R | |
| `summary` | String(1000) | O | LLM-produced gist of the submission |
| `speakerType`, `speakerName` | Enum `Person`/`Agent`, String(200) | O | |
| `principal` | String(100) | R | |
| `status` | Enum `RECEIVED` \| `APPLIED` \| `NO_CHANGES` \| `REJECTED` \| `FAILED` | R | |
| `dryRun` | Boolean | R | Dry runs are **not** persisted. The field exists for the response DTO only. |
| `opsProposed`, `opsApplied`, `opsRejected` | Integer | R | |
| `report` | String(32000) | O | JSON array of rejected operations and reasons, with untrusted strings truncated |
| `llmProvider`, `llmModel`, `promptVersion` | String(100) | R once processed | |
| `occurredAt` | DateTime | O | Client-supplied time of the original event |
| `receivedAt`, `completedAt` | DateTime | R / O | |
| `rawContent` | String(brain `rawContentMaxChars`) | O | Only if the brain sets `retainRawContent = true` |

#### SourceRef

| Property | Type | R/O | Notes |
|---|---|---|---|
| `brainId`, `id` | String | R | |
| `kind` | Enum `FILE` \| `URL` \| `MESSAGE` \| `OTHER` | R | |
| `locator` | String(2000) | R | Path, URL or message id exactly as given. **Never dereferenced.** |
| `locatorNorm` | String(2000) | R | §7.5 |
| `title` | String(200) | O | |
| `summary` | String(2000) | O | Client-provided, or LLM-summarized from text the client supplied |
| `mediaType` | String(100) | O | |
| `clientContentHash` | String(128) | O | Client-provided hash of the file. Not verified. |
| `createdAt`, `lastSeenAt` | DateTime | R | |

#### Conflict

| Property | Type | R/O |
|---|---|---|
| `brainId`, `id` | String | R |
| `kind` | Enum `CONTRADICTION` \| `DUPLICATE_CANDIDATE` \| `PROTECTED_OVERRIDE` | R |
| `status` | Enum `OPEN` \| `RESOLVED` \| `DISMISSED` | R |
| `description` | String(500) | R |
| `createdAt`, `resolvedAt` | DateTime | R / O |
| `resolution` | Enum `KEEP_EXISTING` \| `ACCEPT_INCOMING` \| `KEEP_BOTH` \| `MERGE` \| `NOT_DUPLICATE` | O |
| `resolvedBy`, `resolutionNote` | String(100), String(500) | O |

`PROTECTED_OVERRIDE` means an LLM claim tried to supersede a MANUAL claim, which is never allowed automatically (§9.4).

#### Brain

| Property | Type | R/O |
|---|---|---|
| `id` = `brainId` | String(63), pattern in §7.1 | R |
| `name` | String(100) | R |
| `description` | String(1000) | O |
| `configJson` | String(64000) | R: serialized `BrainConfig` (§11) |
| `configVersion` | Integer | R (optimistic locking) |
| `selfEntityId` | String(36) | R: the brain's `Person` "self" entity (§7.6) |
| `status` | Enum `ACTIVE` \| `DELETING` | R |
| `createdAt`, `updatedAt` | DateTime | R |

#### ApiKey (system)

| Property | Type | Notes |
|---|---|---|
| `id` | String(16) | Public key id, also embedded in the token |
| `name` | String(100) | |
| `secretHash` | String(64) | SHA-256 hex of the secret part (high-entropy random, so no slow KDF is needed) |
| `scopes` | List<String> | `BRAIN_READ`, `BRAIN_WRITE`, `INGEST`, `BRAIN_ADMIN` |
| `brainIds` | List<String> | Brains the key may access. `["*"]` only for admin-created global keys. |
| `createdAt`, `lastUsedAt`, `revokedAt` | DateTime | |

---

## 6. Controlled vocabulary (normative, core profile `core-v1`)

The vocabulary is defined in Java as data, one registry with records. It is not a set of hand-written Cypher strings. The registry renders the LLM prompt section, the JSON schema for the output, the validator rules, and the `GET /vocabulary` response, all from the same records, so they cannot drift apart.

### 6.1 Entity types (15)

| Type | Description given to the LLM | Examples |
|---|---|---|
| `Person` | A specific human being | "Alice Chen", the speaker ("I") |
| `Agent` | A specific software agent, bot or robot that acts | "Claude Code", "warehouse robot R2" |
| `Organization` | A company, team, community, institution or group | "Acme Corp", "Apache Software Foundation" |
| `Project` | A named, bounded effort with a goal (software or not) | "GraphSpindle", "kitchen renovation" |
| `Technology` | A language, framework, library, database, tool, platform or service | "Java", "Neo4j", "Spring AI" |
| `Standard` | A license, specification, protocol, format or regulation | "Apache License 2.0", "MCP", "GDPR" |
| `Artifact` | A concrete work product: repository, document, dataset, model, design | "graph-spindle repo", "Q3 report" |
| `Concept` | An abstract idea, topic, method or field | "knowledge graph", "entity resolution" |
| `Decision` | A choice that was made or proposed, phrased as "Use X for Y" | "Use Apache 2.0 for GraphSpindle" |
| `Task` | A concrete piece of work to be done | "write Neo4j integration tests" |
| `Goal` | A desired outcome or objective | "ship MVP by March" |
| `Event` | Something that happened or will happen at a time | "v0.1 release", "design review 2026-11-20" |
| `Place` | A geographic or physical location | "Berlin", "office lab 3" |
| `Preference` | A stated, durable like, dislike or working preference of a person or agent | "prefers concise answers" |
| `Skill` | A capability or expertise a person or agent has | "Cypher", "technical writing" |

Classification hints the prompt must include:

- A programming language is a `Technology` with `category = LANGUAGE`.
- A license is a `Standard` with `category = LICENSE`.
- An `Artifact` exists only if it is a specific item. Otherwise use `Concept`.
- A `Decision` must have a chosen option. Otherwise record a `Task` or a `Goal`.
- Do **not** create entities for pronouns, dates, numbers, generic nouns ("the code", "a database"), or one-off conversational items.

### 6.2 Type compatibility for entity resolution

A mention may resolve to an existing entity of a different type only within these groups:
`{Technology, Standard, Concept, Skill}`, `{Project, Artifact}`, `{Person, Agent}`, `{Task, Goal}`.
Cross-group matches are rejected by the validator. Within a group, the existing entity's type wins, and the engine never changes an entity's type automatically. A type change is a manual operation.

### 6.3 Relation predicates (projected as typed edges)

`Any` means any enabled entity type. Cardinality is per subject: `ONE` means at most one active positive object per subject, enforced through `slotKey`. `MANY` means unlimited.

| Predicate | Allowed source types | Allowed target types | Card. | Meaning |
|---|---|---|---|---|
| `WORKS_ON` | Person, Agent, Organization | Project, Task, Goal, Artifact | MANY | is actively working on |
| `MEMBER_OF` | Person, Agent, Organization | Organization, Project | MANY | belongs to, or is employed by |
| `CREATED` | Person, Agent, Organization | Project, Technology, Standard, Artifact, Concept | MANY | authored, founded or built |
| `MAINTAINS` | Person, Agent, Organization | Project, Technology, Artifact | MANY | owns or maintains |
| `USES` | Project, Artifact, Technology, Person, Agent, Organization, Task | Technology, Standard, Artifact | MANY | uses as a component or tool |
| `WRITTEN_IN` | Project, Artifact, Technology | Technology | MANY | implemented in a language |
| `DEPENDS_ON` | Project, Artifact, Technology, Task, Goal | Project, Artifact, Technology, Task, Goal | MANY | requires to function or proceed |
| `PART_OF` | Project, Artifact, Technology, Organization, Task, Goal, Event, Place, Concept | the same type as the source, or Project for Task/Artifact | MANY | is a component or sub-part of |
| `LICENSED_UNDER` | Project, Artifact, Technology | Standard | ONE | distributed under the license (brains may set MANY for dual licensing) |
| `IMPLEMENTS` | Project, Artifact, Technology | Standard, Concept | MANY | implements the spec or idea |
| `ALTERNATIVE_TO` | Technology, Standard, Project | Technology, Standard, Project | MANY | can substitute for |
| `IS_A` | Technology, Standard, Concept, Skill | Concept | MANY | is a kind of |
| `ABOUT` | Artifact, Event, Decision, Goal, Task | Any | MANY | has the topic or subject |
| `DECIDED` | Person, Agent, Organization | Decision | MANY | made or approved the decision |
| `APPLIES_TO` | Decision, Preference | Project, Organization, Artifact, Task, Goal, Person, Agent | MANY | is scoped to |
| `CHOOSES` | Decision | Technology, Standard, Artifact, Concept, Organization, Place, Person, Agent, Project | ONE | the selected option |
| `REJECTS` | Decision | (same as CHOOSES) | MANY | a considered and rejected option |
| `REPLACES` | Decision | Decision | ONE | the new decision replaces the old one |
| `ASSIGNED_TO` | Task | Person, Agent | MANY | is to be done by |
| `BLOCKS` | Task | Task, Goal | MANY | prevents progress on |
| `CONTRIBUTES_TO` | Project, Task, Decision, Event | Goal | MANY | advances the goal |
| `PARTICIPATED_IN` | Person, Agent, Organization | Event | MANY | took part in |
| `LOCATED_IN` | Person, Organization, Event, Place, Artifact | Place | ONE | located in or took place at |
| `KNOWS` | Person, Agent | Person, Agent | MANY | knows or works with |
| `HAS_PREFERENCE` | Person, Agent | Preference | MANY | holds the preference |
| `HAS_SKILL` | Person, Agent | Skill, Technology | MANY | is skilled in |
| `INTERESTED_IN` | Person, Agent, Organization | Any | MANY | is interested in |
| `RELATED_TO` | Any | Any | MANY | fallback only, when nothing more specific fits. The confidence is capped at 0.6, and brains may disable it. |

Self-loops (subject equals object) are rejected for every predicate.

### 6.4 Attribute predicates (literal claims, projected as `attr_<name>`)

All attribute predicates have cardinality ONE unless marked otherwise. Allowed values for enums are fixed lists.

| Predicate | Entity types | Value type | Constraint |
|---|---|---|---|
| `status` | Project | ENUM | `PLANNED, ACTIVE, PAUSED, COMPLETED, ABANDONED` |
| `status` | Task | ENUM | `TODO, IN_PROGRESS, BLOCKED, DONE, CANCELLED` |
| `status` | Goal | ENUM | `OPEN, ACHIEVED, ABANDONED` |
| `status` | Decision | ENUM | `PROPOSED, ACCEPTED, REVERSED, SUPERSEDED` |
| `category` | Technology | ENUM | `LANGUAGE, FRAMEWORK, LIBRARY, DATABASE, TOOL, PLATFORM, SERVICE, MODEL, OTHER` |
| `category` | Standard | ENUM | `LICENSE, SPECIFICATION, PROTOCOL, FORMAT, REGULATION, OTHER` |
| `category` | Artifact | ENUM | `REPOSITORY, DOCUMENT, DATASET, MODEL, DESIGN, MEDIA, OTHER` |
| `category` | Organization | ENUM | `COMPANY, TEAM, COMMUNITY, INSTITUTION, GOVERNMENT, OTHER` |
| `category` | Place | ENUM | `COUNTRY, REGION, CITY, SITE, ROOM, VIRTUAL, OTHER` |
| `role` | Person, Agent | STRING(100) | e.g. "backend engineer" |
| `version` | Technology, Standard, Artifact | STRING(50) | |
| `homepage` | Project, Technology, Standard, Organization, Artifact | URL(2000) | stored only, never fetched |
| `rationale` | Decision | STRING(500) | |
| `decidedOn` | Decision | PARTIAL_DATE | |
| `dueDate` | Task, Goal | PARTIAL_DATE | |
| `startDate` | Event, Project | PARTIAL_DATE | |
| `endDate` | Event, Project | PARTIAL_DATE | |
| `priority` | Task, Goal | ENUM | `LOW, MEDIUM, HIGH, CRITICAL` |
| `strength` | Preference | ENUM | `WEAK, MODERATE, STRONG` |
| `detail` | Preference, Skill | STRING(300) | |

Rules:

- `status = SUPERSEDED` on a Decision is set only by the engine, as a consequence of `REPLACES`. The LLM cannot assert it directly.
- `URL` values must match `^https?://` or `^file:` with length ≤ 2000. They are validated by `java.net.URI` parsing, and URLs with user information (`https://user:password@host/`) are rejected (ADR-021).
- `PARTIAL_DATE` values must match `^\d{4}(-\d{2}(-\d{2})?)?$` and be a valid calendar date.
- `NUMBER` is reserved for custom attributes. It is stored as a canonical decimal string. At most 38 significant digits and an exponent (scale) between -38 and 38 are accepted, so the plain form stays short.

### 6.5 What is never stored

- Full conversations and transcripts (unless `retainRawContent`), raw file contents, and secrets.
- Credentials that appear in input. The extraction prompt instructs the model to drop them, and the validator rejects any string matching the brain's secret patterns (default: common API-key and token regexes, `-----BEGIN`, `password\s*[:=]`).
- Ephemeral chit-chat, greetings, and the assistant's own reasoning.

---

## 7. Identifiers and normalization (normative)

### 7.1 Brain id

The pattern is `^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$`. The id is chosen by the creator and immutable. The ids `admin`, `system`, `all` and `_*` are reserved.

### 7.2 Node ids

UUIDv7, lowercase hex with dashes, generated by the engine. They are globally unique in practice but are always looked up together with `brainId`. Clients and the LLM never choose ids.

### 7.3 Name normalization `norm(s)`

Applied in this order:

1. Unicode NFKC.
2. Strip control characters, including zero-width characters and the bidi overrides U+200B–U+200F and U+202A–U+202E.
3. Trim and collapse internal whitespace to a single space.
4. Lowercase with `Locale.ROOT`.
5. Strip surrounding quotes and brackets and trailing `.,;:!?`.
6. Remove diacritics: NFD, drop the combining diacritical marks (U+0300–036F, U+1AB0–1AFF, U+1DC0–1DFF, U+20D0–20FF, U+FE20–FE2F), then NFC. Other non-spacing marks are letters of their scripts (Japanese voicing marks, Indic vowel signs, Hebrew/Arabic points) and are kept, so ガス ≠ カス and मुंबई ≠ मबई (ADR-021).
7. Remove a leading `the `.
8. Truncate to 200 code points (a safety bound only, see below).

Symbols such as `+ # . / -` are **kept**, so "C++", "C#" and "C" stay distinct. Results that are empty or longer than 200 are rejected: validators compute steps 1–7 untruncated (`Normalizer.normUntruncated`) and reject a name or alias whose normalized form exceeds 200 code points (NFKC can expand a short input), so truncation can never make two distinct names share a `canonicalKey`. Normalization is pure and has a table-driven unit test.

`canonicalKey = type.toLowerCase(Locale.ROOT) + ":" + normalizedName`. If an entity is created **explicitly as distinct** from an existing one with the same key (manual API only, flag `distinct=true`), the engine appends `~2`, `~3` and so on. The LLM can never request distinct creation, so a canonicalKey collision on LLM create always means "same entity".

### 7.4 Claim keys

```
objPart    = RELATION ? objectId : valueType + "|" + valueNorm
activeKey  = sha256hex(brainId + "\u001f" + subjectId + "\u001f" + predicate + "\u001f" + objPart + "\u001f" + polarity)
slotKey    = sha256hex(brainId + "\u001f" + subjectId + "\u001f" + predicate)      // only cardinality ONE, POSITIVE
valueNorm  = STRING: norm(value) without truncation (steps 1–7);  ENUM: upper-case token;  PARTIAL_DATE: as given;  BOOLEAN: "true"/"false";
             NUMBER: BigDecimal.stripTrailingZeros().toPlainString();  URL: §7.5 normalization
```

Both keys are written only while `status = ACTIVE` and removed (`REMOVE c.activeKey, c.slotKey`) on any other status. Including `brainId` in the hash is defence in depth. The constraints are composite with `brainId` anyway.

### 7.5 Source locator normalization

- URL: lowercase the scheme and host, drop the default port, drop the fragment, keep the query, remove a trailing `/` on the path.
- FILE: unify path separators to `/` and collapse `./` and duplicate `/`. Case is preserved.
- Others: `norm()` without truncation (steps 1–7).

The key is `(brainId, kind, locatorNorm)`.

### 7.6 The "self" entity and first-person statements

At brain creation the engine creates `(:Entity:Person {origin: SYSTEM, name: <brain.ownerName or "Me">, canonicalKey: "person:@self"})` and stores its id in `Brain.selfEntityId`. `@self` cannot be produced by `norm()` from any real name, because the engine rejects mention names that start with `@`.

First-person references are resolved as follows:

- If the ingestion request carries `speaker {type, name}`, "I"/"we" resolve to that Person or Agent through normal entity resolution.
- Otherwise they resolve to the self entity.

The LLM marks first-person mentions with `"isSpeaker": true` and never invents a name for them.

---

## 8. Constraints and indexes (normative Cypher)

Executed idempotently at startup by `SchemaInitializer`, in `graph` module order. All of these work on Neo4j Community / harness. **VERIFY** that composite uniqueness and `IF NOT EXISTS` behave as expected on 2026.10.x (they do on 5.x).

```cypher
// ---- uniqueness (per brain) ----
CREATE CONSTRAINT brain_id_unique          IF NOT EXISTS FOR (b:Brain)     REQUIRE b.id IS UNIQUE;
CREATE CONSTRAINT entity_id_unique         IF NOT EXISTS FOR (n:Entity)    REQUIRE (n.brainId, n.id) IS UNIQUE;
CREATE CONSTRAINT entity_key_unique        IF NOT EXISTS FOR (n:Entity)    REQUIRE (n.brainId, n.canonicalKey) IS UNIQUE;
CREATE CONSTRAINT alias_unique             IF NOT EXISTS FOR (a:Alias)     REQUIRE (a.brainId, a.normalized) IS UNIQUE;
CREATE CONSTRAINT claim_id_unique          IF NOT EXISTS FOR (c:Claim)     REQUIRE (c.brainId, c.id) IS UNIQUE;
CREATE CONSTRAINT claim_active_unique      IF NOT EXISTS FOR (c:Claim)     REQUIRE (c.brainId, c.activeKey) IS UNIQUE;
CREATE CONSTRAINT claim_slot_unique        IF NOT EXISTS FOR (c:Claim)     REQUIRE (c.brainId, c.slotKey) IS UNIQUE;
CREATE CONSTRAINT ingestion_id_unique      IF NOT EXISTS FOR (i:Ingestion) REQUIRE (i.brainId, i.id) IS UNIQUE;
CREATE CONSTRAINT ingestion_request_unique IF NOT EXISTS FOR (i:Ingestion) REQUIRE (i.brainId, i.requestId) IS UNIQUE;
CREATE CONSTRAINT source_id_unique         IF NOT EXISTS FOR (s:SourceRef) REQUIRE (s.brainId, s.id) IS UNIQUE;
CREATE CONSTRAINT source_locator_unique    IF NOT EXISTS FOR (s:SourceRef) REQUIRE (s.brainId, s.kind, s.locatorNorm) IS UNIQUE;
CREATE CONSTRAINT conflict_id_unique       IF NOT EXISTS FOR (x:Conflict)  REQUIRE (x.brainId, x.id) IS UNIQUE;
CREATE CONSTRAINT apikey_id_unique         IF NOT EXISTS FOR (k:ApiKey)    REQUIRE k.id IS UNIQUE;

// ---- range indexes for scoped filtering ----
CREATE INDEX entity_brain_type      IF NOT EXISTS FOR (n:Entity)    ON (n.brainId, n.type, n.status);
CREATE INDEX entity_brain_updated   IF NOT EXISTS FOR (n:Entity)    ON (n.brainId, n.updatedAt);
CREATE INDEX claim_brain_subject    IF NOT EXISTS FOR (c:Claim)     ON (c.brainId, c.subjectId, c.status);
CREATE INDEX claim_brain_object     IF NOT EXISTS FOR (c:Claim)     ON (c.brainId, c.objectId, c.status);
CREATE INDEX claim_brain_predicate  IF NOT EXISTS FOR (c:Claim)     ON (c.brainId, c.predicate, c.status);
CREATE INDEX ingestion_brain_hash   IF NOT EXISTS FOR (i:Ingestion) ON (i.brainId, i.contentHash);
CREATE INDEX ingestion_brain_time   IF NOT EXISTS FOR (i:Ingestion) ON (i.brainId, i.receivedAt);
CREATE INDEX conflict_brain_status  IF NOT EXISTS FOR (x:Conflict)  ON (x.brainId, x.status);

// ---- fulltext (search + fuzzy candidate lookup) ----
CREATE FULLTEXT INDEX entity_text IF NOT EXISTS FOR (n:Entity) ON EACH [n.name, n.description];
CREATE FULLTEXT INDEX claim_text  IF NOT EXISTS FOR (c:Claim)  ON EACH [c.statement];
CREATE FULLTEXT INDEX source_text IF NOT EXISTS FOR (s:SourceRef) ON EACH [s.title, s.summary];
```

Fulltext indexes are **not** brain-partitioned. Every fulltext query must filter in the same statement and oversample:

```cypher
CALL db.index.fulltext.queryNodes('entity_text', $luceneQuery, {limit: $limit * 5}) YIELD node, score
WHERE node.brainId = $brainId AND node.status = 'ACTIVE'
RETURN node, score ORDER BY score DESC LIMIT $limit
```

`$luceneQuery` is built by the engine from user text with every Lucene special character escaped, and it is never passed through raw. The `brainId` in the WHERE is mandatory. A repository test asserts that every Cypher string in the `graph` module contains `$brainId` (ApiKey and Brain-list statements are allowlisted). **VERIFY** the `queryNodes` option-map syntax on 2026.x.

Isolation note: in Community there is no row-level security and no database-per-brain (multiple databases are Enterprise). Isolation is therefore enforced by the single `graph` module, `BrainScope`-typed parameters, the constraints above, and tests (ARCHITECTURE §8). DECISIONS ADR-004 records this.

Vector search is out of MVP scope. Later work may add a Neo4j vector index on `Entity.embedding` and `Claim.embedding` through Spring AI `EmbeddingModel`.

---

## 9. Write semantics (normative)

### 9.1 Creating or finding an entity

```cypher
MERGE (n:Entity {brainId: $brainId, canonicalKey: $canonicalKey})
ON CREATE SET n.id = $id, n.type = $type, n.name = $name, n.normalizedName = $normalizedName,
              n.description = $description, n.status = 'ACTIVE', n.origin = $origin,
              n.createdAt = datetime(), n.updatedAt = datetime(), n.mentionCount = 0,
              n.createdByIngestionId = $ingestionId
WITH n
CALL (n) { SET n:$($type) }        // dynamic label, VERIFY Cypher 25 syntax; fallback: label appended from the validated enum, never from input
RETURN n
```

The `MERGE` key is backed by the `entity_key_unique` constraint, which makes concurrent creation safe. If the merged node turns out to be `status = MERGED`, the engine follows `MERGED_INTO` (at most 5 hops) and uses the survivor. Every name and normalized name is upserted as an `Alias` (`MERGE (a:Alias {brainId, normalized})`), and a `HAS_ALIAS` edge is merged.

### 9.2 Asserting a claim

Given validated `(subject, predicate, object|value, polarity)`:

1. Compute `activeKey`. If an ACTIVE claim with that key exists, **reinforce** it: add `EVIDENCED_BY` (merging on the ingestion), set `evidenceCount+1` and `confidence = max(old, new)`, then stop.
2. For cardinality ONE and POSITIVE, look up the ACTIVE claim with the same `slotKey`, called `existing`. Also look for an ACTIVE claim on the exact same triple with the opposite polarity.
3. If there is no conflicting claim, create the claim as ACTIVE with its keys, create `SUBJECT`, `OBJECT` and `EVIDENCED_BY` edges, and project it (§9.3).
4. If there is a conflicting claim, apply the conflict policy (§9.4).

### 9.3 Projection

- Relation claim becomes ACTIVE and POSITIVE: `MERGE (s)-[r:$($predicate) {brainId:$brainId, claimId:$claimId}]->(o)`.
- It leaves ACTIVE: `MATCH ()-[r {brainId:$brainId, claimId:$claimId}]->() DELETE r`.
- Attribute claim: `SET s['attr_' + $predicate] = $value` (the property name is from the vocabulary), or `REMOVE`, both in the same transaction as the status change.

There is a `ProjectionVerifier` (admin endpoint and test helper) that recomputes projections from claims and reports drift.

### 9.4 Conflict policy

The policy is configurable per brain (`conflictPolicy`). The default is `EXPLICIT_CORRECTIONS_SUPERSEDE`.

| Situation | Default outcome |
|---|---|
| The incoming claim is a `SupersedeClaim` op targeting `existing`, with `explicitCorrection = true` and an evidence quote, `existing.origin != MANUAL`, and the brain allows LLM supersession | `existing` becomes SUPERSEDED (keys removed, projection removed), the new claim becomes ACTIVE, and `(new)-[:SUPERSEDES {reason}]->(existing)` is created. Conflict policy `ALWAYS_FLAG` instead routes this to the next row. |
| The incoming claim contradicts `existing` (same slot or opposite polarity) **without** an explicit correction | The new claim is DISPUTED (no keys, no projection), `existing` stays ACTIVE, and a `Conflict {kind: CONTRADICTION}` is created with `INVOLVES` edges to both claims (roles EXISTING and INCOMING) and `RAISED_BY` to the ingestion |
| The incoming claim would supersede a `MANUAL` claim | As in the previous row, but with `kind: PROTECTED_OVERRIDE` |
| `RetractClaim` with explicit evidence on a non-MANUAL claim | RETRACTED with keys and projection removed. A MANUAL claim gets a `PROTECTED_OVERRIDE` conflict instead. |
| `validTo` is stated ("we stopped using Kafka in 2025") | The existing claim gets `validTo` set **and** status SUPERSEDED by a NEGATIVE claim with `validFrom = 2025`. Both are kept. |

Policy `LATEST_WINS` is offered for single-agent brains. Under it, any contradiction from the same principal supersedes. It is never applied to MANUAL claims.

Conflict resolution (manual API and MCP):

- `KEEP_EXISTING`: the incoming claim becomes RETRACTED.
- `ACCEPT_INCOMING`: supersede as in the first row.
- `KEEP_BOTH`: only for cardinality-MANY claims that have opposite polarity because of different time ranges. It requires `validFrom`/`validTo` edits.
- `MERGE` and `NOT_DUPLICATE`: for DUPLICATE_CANDIDATE conflicts.

### 9.5 Entity merge (manual or conflict resolution only)

Merging `loser` into `survivor` happens in one transaction:

1. Every claim of `loser` is re-pointed (`SUBJECT`/`OBJECT` edges and the denormalized ids).
2. `activeKey`/`slotKey` are recomputed. Collisions are handled as reinforcement for identical claims, or as a CONTRADICTION conflict for slot collisions, in which case the claim from `loser` becomes DISPUTED.
3. Aliases are moved, and projections are rebuilt for both nodes.
4. `loser.status = MERGED`, `mergedIntoId` is set, and `MERGED_INTO` is created.

The LLM can only *propose* a merge, which produces a DUPLICATE_CANDIDATE conflict. It never performs one.

### 9.6 Corrections by users

The manual REST/MCP operations `supersedeClaim`, `retractClaim`, `assertClaim(origin=MANUAL)`, `updateEntity` (which sets `descriptionLocked`), `archiveEntity` and `mergeEntities` are the user correction path. They are always allowed for principals with `BRAIN_WRITE` and never require the LLM.

---

## 10. Worked examples

Brain `dev`, no `speaker` given, so "I" is the self entity `P0 = person:@self`. Each operation list below is what the **reconcile** stage of the LLM returns (contract in ARCHITECTURE §4). For readability, the examples use the same short label (`e1`, `c13`) both for a node and for the opaque candidate handle the LLM sees. In reality the engine assigns per-ingestion handles such as `k1` and `c1` (ARCHITECTURE §3, stage 2) and maps them back to UUIDv7 ids.

### 10.1 Message 1 (README example)

> "I am building GraphSpindle in Java. It uses Neo4j and Spring AI. I decided to use Apache 2.0."

Candidate lookup on an empty brain finds nothing except the self entity.

Proposed operations:

```json
{
  "summary": "Speaker is building the GraphSpindle project in Java with Neo4j and Spring AI, licensed Apache 2.0.",
  "operations": [
    {"op":"create_entity","ref":"e1","type":"Project","name":"GraphSpindle","description":"Knowledge graph engine being built by the speaker."},
    {"op":"create_entity","ref":"e2","type":"Technology","name":"Java"},
    {"op":"create_entity","ref":"e3","type":"Technology","name":"Neo4j"},
    {"op":"create_entity","ref":"e4","type":"Technology","name":"Spring AI"},
    {"op":"create_entity","ref":"e5","type":"Standard","name":"Apache License 2.0","aliases":["Apache 2.0","Apache-2.0"]},
    {"op":"create_entity","ref":"e6","type":"Decision","name":"Use Apache License 2.0 for GraphSpindle"},
    {"op":"assert_claim","subject":{"id":"P0"},"predicate":"WORKS_ON","object":{"ref":"e1"},"statement":"The speaker is building GraphSpindle.","evidence":"I am building GraphSpindle","confidence":0.95},
    {"op":"assert_claim","subject":{"ref":"e1"},"predicate":"WRITTEN_IN","object":{"ref":"e2"},"statement":"GraphSpindle is written in Java.","evidence":"building GraphSpindle in Java","confidence":0.95},
    {"op":"assert_claim","subject":{"ref":"e1"},"predicate":"USES","object":{"ref":"e3"},"statement":"GraphSpindle uses Neo4j.","evidence":"It uses Neo4j and Spring AI","confidence":0.95},
    {"op":"assert_claim","subject":{"ref":"e1"},"predicate":"USES","object":{"ref":"e4"},"statement":"GraphSpindle uses Spring AI.","evidence":"It uses Neo4j and Spring AI","confidence":0.95},
    {"op":"assert_claim","subject":{"ref":"e2"},"predicate":"category","value":"LANGUAGE","statement":"Java is a programming language.","evidence":"in Java","confidence":0.9},
    {"op":"assert_claim","subject":{"ref":"e3"},"predicate":"category","value":"DATABASE","statement":"Neo4j is a database.","evidence":"uses Neo4j","confidence":0.8},
    {"op":"assert_claim","subject":{"ref":"e4"},"predicate":"category","value":"FRAMEWORK","statement":"Spring AI is a framework.","evidence":"Spring AI","confidence":0.7},
    {"op":"assert_claim","subject":{"ref":"e5"},"predicate":"category","value":"LICENSE","statement":"Apache 2.0 is a license.","evidence":"use Apache 2.0","confidence":0.9},
    {"op":"assert_claim","subject":{"id":"P0"},"predicate":"DECIDED","object":{"ref":"e6"},"statement":"The speaker decided to use Apache 2.0.","evidence":"I decided to use Apache 2.0","confidence":0.95},
    {"op":"assert_claim","subject":{"ref":"e6"},"predicate":"APPLIES_TO","object":{"ref":"e1"},"statement":"The license decision applies to GraphSpindle.","evidence":"I decided to use Apache 2.0","confidence":0.8},
    {"op":"assert_claim","subject":{"ref":"e6"},"predicate":"CHOOSES","object":{"ref":"e5"},"statement":"The decision chooses Apache License 2.0.","evidence":"I decided to use Apache 2.0","confidence":0.95},
    {"op":"assert_claim","subject":{"ref":"e6"},"predicate":"status","value":"ACCEPTED","statement":"The Apache 2.0 decision is accepted.","evidence":"I decided","confidence":0.9},
    {"op":"assert_claim","subject":{"ref":"e1"},"predicate":"LICENSED_UNDER","object":{"ref":"e5"},"statement":"GraphSpindle is licensed under Apache License 2.0.","evidence":"I decided to use Apache 2.0","confidence":0.85}
  ]
}
```

Resulting graph:

- 6 entities plus the self entity.
- One alias per normalized name, plus `apache 2.0` and `apache-2.0` pointing to e5.
- 13 claims, all ACTIVE.
- Projection edges: `(P0)-[:WORKS_ON]->(GraphSpindle)`, `(GraphSpindle)-[:WRITTEN_IN]->(Java)`, `(GraphSpindle)-[:USES]->(Neo4j)`, `(GraphSpindle)-[:USES]->(Spring AI)`, `(GraphSpindle)-[:LICENSED_UNDER]->(Apache License 2.0)`, `(P0)-[:DECIDED]->(Decision)`, `(Decision)-[:APPLIES_TO]->(GraphSpindle)`, `(Decision)-[:CHOOSES]->(Apache License 2.0)`.
- Properties: `Java.attr_category = 'LANGUAGE'`, `Decision.attr_status = 'ACCEPTED'`, and so on.
- One `Ingestion` (status APPLIED, opsApplied 19), with every claim `EVIDENCED_BY` it and every entity `MENTIONED_IN` it. No raw text is stored.

Query "what does GraphSpindle use?":

```cypher
MATCH (p:Entity {brainId:$brainId, canonicalKey:'project:graphspindle'})-[r:USES|WRITTEN_IN]->(t:Entity {brainId:$brainId}) RETURN type(r), t.name
```

### 10.2 Message 2: update, no duplicates

> "GraphSpindle now exposes an MCP server too. Alice Chen from Acme is helping me with the Neo4j store. Design notes are in docs/ARCHITECTURE.md."
> Request `sources: [{kind: FILE, locator: "docs/ARCHITECTURE.md", summary: "Architecture of GraphSpindle"}]`

Extraction mentions: GraphSpindle (Project), MCP (Standard), Alice Chen (Person), Acme (Organization), Neo4j (Technology), "me" (speaker).

Candidate lookup:

- `alias 'graphspindle'` resolves to e1 (score 1.0, type Project, exact).
- `alias 'neo4j'` resolves to e3.
- MCP, Alice Chen and Acme have no candidates.

The reconcile stage is given e1 and e3 with their active claims, and returns:

```json
{"operations":[
  {"op":"create_entity","ref":"m1","type":"Standard","name":"Model Context Protocol","aliases":["MCP"]},
  {"op":"create_entity","ref":"a1","type":"Person","name":"Alice Chen"},
  {"op":"create_entity","ref":"o1","type":"Organization","name":"Acme"},
  {"op":"assert_claim","subject":{"id":"e1"},"predicate":"IMPLEMENTS","object":{"ref":"m1"},"statement":"GraphSpindle exposes an MCP server.","evidence":"GraphSpindle now exposes an MCP server","confidence":0.9},
  {"op":"assert_claim","subject":{"ref":"a1"},"predicate":"MEMBER_OF","object":{"ref":"o1"},"statement":"Alice Chen is from Acme.","evidence":"Alice Chen from Acme","confidence":0.85},
  {"op":"assert_claim","subject":{"ref":"a1"},"predicate":"WORKS_ON","object":{"id":"e1"},"statement":"Alice Chen helps with GraphSpindle's Neo4j store.","evidence":"Alice Chen from Acme is helping me with the Neo4j store","confidence":0.8},
  {"op":"assert_claim","subject":{"ref":"a1"},"predicate":"KNOWS","object":{"id":"P0"},"statement":"Alice Chen works with the speaker.","evidence":"is helping me","confidence":0.7},
  {"op":"assert_claim","subject":{"id":"e1"},"predicate":"USES","object":{"id":"e3"},"statement":"GraphSpindle uses Neo4j.","evidence":"the Neo4j store","confidence":0.8},
  {"op":"link_source","source":0,"target":{"id":"e1"}}
]}
```

Effects:

- `USES Neo4j` already has an ACTIVE claim with the same `activeKey`, so it is **reinforced**: `evidenceCount` goes to 2 and no new claim or edge is created.
- GraphSpindle is the same node, and its `mentionCount` becomes 2.
- `SourceRef {kind: FILE, locator: "docs/ARCHITECTURE.md"}` is created (never read) and gets `DESCRIBES` to e1 and `SUBMITTED_IN` to the ingestion.

### 10.3 Message 3: explicit correction

> "Change of plan: we're switching GraphSpindle from Apache 2.0 to the MIT license."

Candidates:

- GraphSpindle resolves to e1.
- `apache 2.0` resolves to e5 by alias.
- MIT has none.
- The reconcile context includes e1's ACTIVE slot claim `LICENSED_UNDER → e5` (claim `c13`) and decision e6 with its `CHOOSES` and `status` claims (`c11`, `c12`).

```json
{"operations":[
  {"op":"create_entity","ref":"s1","type":"Standard","name":"MIT License","aliases":["MIT"]},
  {"op":"assert_claim","subject":{"ref":"s1"},"predicate":"category","value":"LICENSE","statement":"MIT is a license.","evidence":"the MIT license","confidence":0.9},
  {"op":"create_entity","ref":"d2","type":"Decision","name":"Use MIT License for GraphSpindle"},
  {"op":"assert_claim","subject":{"id":"P0"},"predicate":"DECIDED","object":{"ref":"d2"},"statement":"The speaker decided to switch to MIT.","evidence":"we're switching GraphSpindle from Apache 2.0 to the MIT license","confidence":0.9},
  {"op":"assert_claim","subject":{"ref":"d2"},"predicate":"CHOOSES","object":{"ref":"s1"},"statement":"The decision chooses MIT.","evidence":"to the MIT license","confidence":0.9},
  {"op":"assert_claim","subject":{"ref":"d2"},"predicate":"APPLIES_TO","object":{"id":"e1"},"statement":"Applies to GraphSpindle.","evidence":"switching GraphSpindle","confidence":0.9},
  {"op":"assert_claim","subject":{"ref":"d2"},"predicate":"REPLACES","object":{"id":"e6"},"statement":"The MIT decision replaces the Apache 2.0 decision.","evidence":"Change of plan","confidence":0.9},
  {"op":"supersede_claim","claimId":"c13","explicitCorrection":true,"reason":"License changed from Apache 2.0 to MIT",
   "replacement":{"subject":{"id":"e1"},"predicate":"LICENSED_UNDER","object":{"ref":"s1"},"statement":"GraphSpindle is licensed under MIT.","evidence":"switching GraphSpindle from Apache 2.0 to the MIT license","confidence":0.9}}
]}
```

Effects:

- `c13` becomes SUPERSEDED and its `LICENSED_UNDER` edge to Apache is deleted. The new claim `c21` is ACTIVE with a `LICENSED_UNDER` edge to MIT, and `(c21)-[:SUPERSEDES]->(c13)`.
- **Engine consequence rule:** a `REPLACES` claim from d2 to e6 makes the engine supersede e6's `status = ACCEPTED` claim with a SYSTEM claim `status = SUPERSEDED`. The LLM cannot assert that value (§6.4).
- History query: `MATCH (c:Claim {brainId:$b, subjectId:$e1, predicate:'LICENSED_UNDER'}) RETURN c ORDER BY c.recordedAt` returns both claims.

### 10.4 Message 4: implicit contradiction from another agent

Agent key `ci-bot` submits:

> "GraphSpindle is GPL-3.0 licensed."

The reconcile stage sees the active slot claim `c21` (MIT). The model does **not** mark this as an explicit correction, because no correction language is present, so it emits a plain `assert_claim` for `e1 LICENSED_UNDER GPL-3.0` (creating Standard g1).

Effects:

- The slot is occupied, so the new claim `c25` is stored **DISPUTED** without keys or projection.
- `Conflict {kind: CONTRADICTION, status: OPEN, description: "GraphSpindle license: MIT (active) vs GPL-3.0 (incoming)"}` is created with `INVOLVES {role: EXISTING}` to c21, `INVOLVES {role: INCOMING}` to c25, and `RAISED_BY` to the ingestion.
- Retrieval keeps returning MIT, and the entity context shows `openConflicts: 1`.
- A user resolves the conflict with `POST /conflicts/{id}/resolve {resolution: KEEP_EXISTING}`, which makes c25 RETRACTED.

If the same text had said "Correction: GraphSpindle is actually GPL-3.0", the outcome would depend on policy. Under the default policy it would supersede, because c21 is LLM-origin. Had c21 been MANUAL, a `PROTECTED_OVERRIDE` conflict would have been raised instead.

### 10.5 Duplicate candidate

> "Graph Spindle's repo is on GitHub."

- `norm("Graph Spindle") = "graph spindle"`, which is not an alias, so there is no exact match.
- Fulltext finds e1 with a high score.
- The reconcile stage may answer `{"id":"e1"}`, which is accepted because e1 is in the candidate set and the type is compatible. It may also create `Project "Graph Spindle"` plus `{"op":"propose_merge","a":{"ref":"x"},"b":{"id":"e1"},"reason":"spacing variant"}`, which yields a DUPLICATE_CANDIDATE conflict.
- If the stage creates a new entity without proposing a merge while a fulltext candidate of the same type scored above `duplicateSuspicionScore`, the engine **itself** opens a DUPLICATE_CANDIDATE conflict.

---

## 11. Vocabulary extensibility (normative)

The vocabulary is fixed at runtime per brain. It comes from **code** (core profile) plus the **brain configuration** (subset and custom additions) set through the admin API. The LLM never adds to it, and unknown types or predicates in LLM output are rejected with reason `UNKNOWN_TYPE` or `UNKNOWN_PREDICATE`.

```jsonc
// BrainConfig.vocabulary
{
  "profile": "core-v1",
  "enabledEntityTypes": ["Person","Project","Technology","Standard","Decision","Task","Concept"], // null = all core types
  "disabledPredicates": ["RELATED_TO"],                                                          // removes from core
  "predicateOverrides": { "LICENSED_UNDER": { "cardinality": "MANY" } },                         // only cardinality may be overridden
  "customEntityTypes": [
    { "name": "Recipe", "description": "A cooking recipe", "compatibilityGroup": null,
      "attributes": [ { "name": "cuisine", "valueType": "STRING", "maxLength": 50 },
                      { "name": "difficulty", "valueType": "ENUM", "values": ["EASY","MEDIUM","HARD"] } ] }
  ],
  "customRelationPredicates": [
    { "name": "INGREDIENT_OF", "description": "is an ingredient of", "sourceTypes": ["Concept"], "targetTypes": ["Recipe"], "cardinality": "MANY" }
  ],
  "customAttributePredicates": [
    { "name": "servings", "entityTypes": ["Recipe"], "valueType": "NUMBER", "min": 1, "max": 100 }
  ]
}
```

Bounds, enforced by `VocabularyConfigValidator` when the config is saved:

| Item | Rule |
|---|---|
| Custom entity type name | `^[A-Z][A-Za-z0-9]{1,39}$`. Not a core type, not a reserved label (§5.2), and unique, all compared **ignoring case** (the canonical key lowercases the type). At most 20 per brain. Description ≤ 200. |
| Custom relation predicate | `^[A-Z][A-Z0-9_]{1,39}$`. Not a system relationship type or core predicate, ignoring case. At most 30. Source and target types must be enabled. |
| Attribute name | `^[a-z][A-Za-z0-9]{1,39}$`, unique per entity type ignoring case, not a core predicate or custom relation name differing only in case, at most 10 per type. `valueType ∈ {STRING, ENUM, PARTIAL_DATE, BOOLEAN, NUMBER, URL}`. ENUM has at most 30 values, each matching `^[A-Z][A-Z0-9_]{0,39}$`. STRING `maxLength` ≤ 500. |
| Disabling | `Person` cannot be disabled (the self entity), and system labels cannot be disabled. Disabling a type with existing entities is allowed: those entities become read-only for ingestion and remain retrievable. |
| Removing a custom type or predicate | Rejected while active claims or entities use it (409), unless `force=true` from `BRAIN_ADMIN`, which archives them. |
| Total | At most 40 entity types and 60 predicates enabled, which keeps the prompt within budget. |

Changing the vocabulary bumps `Brain.configVersion`. The prompt cache key includes it.

---

## 12. Open questions and things to verify

- **VERIFY:** dynamic labels and relationship types (`SET n:$($type)`, `MERGE ()-[:$($t)]->()`) in the Cypher version shipped with Neo4j 2026.10 and harness 2026.10. Fallback: build the label or type token from the validated vocabulary record and quote it with backticks. It must never come from request text.
- **VERIFY:** composite uniqueness constraints (`REQUIRE (a, b) IS UNIQUE`) in Community/harness 2026.10, and that nodes missing one of the properties are exempt (this is what makes `activeKey` and `slotKey` work).
- **VERIFY:** fulltext `queryNodes` option map and analyzer defaults, and whether the `standard-no-stop-words` analyzer should be configured so that names like "The Who" are not dropped.
- Multilingual normalization (Turkish dotless i and the like) is handled only by `Locale.ROOT`. Acceptable for MVP.
- Embedding-based resolution is deferred. The fulltext and alias path must hit ≥ 95% on the duplicate test corpus (IMPLEMENTATION_PLAN M7).
