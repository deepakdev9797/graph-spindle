package io.graphspindle.llm;

/**
 * The engine's only door to a language model (ARCHITECTURE §7, ADR-027). Implementations must not
 * give the model tools, memory or advisors, must not retry by themselves, and must honour the
 * request's timeout. The default implementation is {@link SpringAiIngestionLlm} over whatever Spring
 * AI {@code ChatModel} is configured (Ollama, OpenAI or OpenAI-compatible, Anthropic, or a test
 * double); another implementation can replace it as a bean.
 */
public interface IngestionLlm {

    /** True when a model is configured and enabled. */
    boolean available();

    /** Short provider name for provenance and the data-transfer notice ({@code ollama}, {@code openai} ...). */
    String provider();

    /** The configured model name, if known. */
    String model();

    /**
     * One completion.
     *
     * @throws LlmException on unavailability, timeout or a provider failure
     */
    LlmReply complete(LlmRequest request);
}
