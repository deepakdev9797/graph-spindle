/**
 * The LLM boundary (ARCHITECTURE §7, ADR-027): {@link io.graphspindle.llm.IngestionLlm} over any
 * Spring AI {@code ChatModel}, versioned prompt templates and untrusted-data delimiters. Knows nothing
 * about Neo4j or the graph.
 */
package io.graphspindle.llm;
