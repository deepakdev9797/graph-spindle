package io.graphspindle.llm;

import java.time.Duration;
import java.util.Objects;

/**
 * One prompt. {@code system} and {@code user} are final strings: they are never passed through a
 * template renderer (the prompts contain JSON braces, and user text must never be expanded).
 *
 * @param promptId versioned prompt id, for provenance ({@code reconcile-v1})
 * @param jsonSchema the JSON schema of the expected answer, or null
 * @param forceSchema send the schema as the provider's structured-output format even in PROMPT mode
 *     (used by repair calls, which otherwise tend to reproduce the same syntax error)
 * @param timeout deadline of this call
 */
public record LlmRequest(String promptId, String system, String user, String jsonSchema, boolean forceSchema,
        Duration timeout) {

    public LlmRequest {
        Objects.requireNonNull(promptId, "promptId");
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(timeout, "timeout");
    }
}
