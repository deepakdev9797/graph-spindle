# Coding agent instructions

## Mission

Build GraphSpindle, a single Spring Boot application that accepts unstructured information, uses a configured LLM to infer useful knowledge and reconcile it with an existing Neo4j graph, and provides REST/Swagger and MCP interfaces for multiple independent brains. Read README.md and all docs before coding.

## Non-negotiable product requirements

- Keep one deployable application; no frontend or microservice fleet in the MVP.
- Both REST and MCP must delegate to the same application services and enforce the same authorization and brain boundaries.
- The engine, not the external agent, owns extraction, entity resolution, schema validation, and graph mutations. The LLM is required for intelligent ingestion; deterministic management/retrieval can work without invoking it.
- Use a bounded, explicitly defined graph vocabulary. Do not allow the model to invent labels, relationships, properties, or execute arbitrary Cypher.
- Store useful knowledge and source references, not entire raw files or transcripts by default. Do not automatically fetch URLs or local paths.
- Do not silently merge contradictory facts or overwrite user knowledge. Keep provenance and support correction.
- A brain must never read, modify, resolve entities against, or link to another brain without a separately designed explicit sharing feature (out of scope).

## Proposed engineering defaults

Java 21, Spring Boot, Spring AI, Maven wrapper, Neo4j, OpenAPI/Swagger UI, JUnit 5, Testcontainers, Docker Compose. Choose compatible supported versions after consulting official documentation; do not invent versions or APIs. Use the Spring AI MCP server integration when feasible. Start with one real configurable LLM provider and an offline deterministic test double; keep provider selection replaceable via configuration. Prefer a modular monolith organized by feature and small interfaces at external boundaries.

## Workflow

1. Inspect the repository, existing license, and Git status. Preserve existing work. Record assumptions and decisions in docs/DECISIONS.md or short ADRs.
2. Follow docs/IMPLEMENTATION_PLAN.md. Implement and test one vertical slice at a time. Do not claim a feature works until it has been exercised.
3. If multiple agents are available, assign non-overlapping responsibilities (graph/schema, LLM pipeline, API/MCP, tests/deployment). Establish shared contracts first. Integrate on one branch via reviewed changes; avoid concurrent edits to shared files.
4. Use targeted commits; never push, publish, or deploy without user authorization. Do not alter global configuration, access unrelated files, or provision paid resources without approval.
5. Run the Maven verification command and integration tests where available. Report failed or skipped tests, missing credentials, assumptions, and outstanding issues. Do not weaken tests or security to make a build pass.

## Security and quality

- Treat ingested text, retrieved graph content, and LLM outputs as untrusted data. Prompt injection cannot authorize tools, change brain identity, or override rules. The ingestion LLM must not have arbitrary execution tools.
- Validate typed model output, constrain lengths and counts, use parameterized queries and transactions, and enforce unique scoped identifiers and idempotency at the database/service level.
- Default to local-only deployment; require authentication for any exposed interface and enforce per-brain authorization server-side. Never expose unrestricted MCP or Neo4j endpoints.
- Never commit API keys, tokens, passwords, private data, or raw conversations. Use environment variables and a sanitized .env.example. Redact logs. Explain external LLM data transfer clearly; support local providers where compatible.
- Add tests for cross-brain isolation, malformed model output, duplicates, retries, conflicts, prompt injection, transaction rollback, and REST/MCP parity.

## Completion report

Summarize implemented behavior, how to start it, commands actually executed, test results, security limitations, and next steps. Keep README current and distinguish examples from verified behavior.
