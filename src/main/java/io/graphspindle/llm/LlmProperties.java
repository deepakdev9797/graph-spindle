package io.graphspindle.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider-neutral LLM settings ({@code graphspindle.llm.*}, ADR-027). Provider-specific tuning
 * (Ollama {@code num-ctx}, {@code seed}, {@code think}; OpenAI {@code base-url}; keys) is set on the
 * provider's own {@code spring.ai.<provider>.*} properties and arrives through the model's default
 * options; these values are applied on top for every ingestion call.
 *
 * @param enabled false makes ingestion unavailable (503) even with a configured provider
 * @param model per-call model override; blank = the provider's configured model
 * @param temperature sampling temperature (0 for reproducible extraction)
 * @param maxOutputTokens maximum completion tokens per call
 * @param contextTokens the model's context window, used to refuse requests that cannot fit
 * @param callTimeout deadline of a single call
 * @param timeout deadline of all calls of one ingestion together
 * @param structuredOutput how the JSON answer is requested
 */
@ConfigurationProperties("graphspindle.llm")
public record LlmProperties(Boolean enabled, String model, Double temperature, Integer maxOutputTokens,
        Integer contextTokens, Duration callTimeout, Duration timeout, StructuredOutput structuredOutput) {

    /** How the JSON answer is requested from the provider. */
    public enum StructuredOutput {
        /** Our JSON schema is passed as the provider's native structured-output format (if supported). */
        PROVIDER_SCHEMA,
        /** The schema is only in the prompt; the raw text is parsed (repairs still use the provider schema). */
        PROMPT
    }

    public LlmProperties {
        enabled = enabled == null || enabled;
        model = model == null || model.isBlank() ? null : model.strip();
        temperature = temperature == null ? 0.0 : temperature;
        maxOutputTokens = maxOutputTokens == null ? 4096 : maxOutputTokens;
        contextTokens = contextTokens == null ? 32768 : contextTokens;
        callTimeout = callTimeout == null ? Duration.ofSeconds(90) : callTimeout;
        timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        structuredOutput = structuredOutput == null ? StructuredOutput.PROVIDER_SCHEMA : structuredOutput;
        if (maxOutputTokens <= 0 || contextTokens <= 0 || callTimeout.isNegative() || callTimeout.isZero()
                || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("graphspindle.llm: token counts and timeouts must be positive");
        }
    }

    public static LlmProperties defaults() {
        return new LlmProperties(null, null, null, null, null, null, null, null);
    }
}
