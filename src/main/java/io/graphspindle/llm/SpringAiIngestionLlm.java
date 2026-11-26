package io.graphspindle.llm;

import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link IngestionLlm} over the configured Spring AI {@link ChatModel} (any provider: Ollama,
 * OpenAI and OpenAI-compatible servers, Anthropic, or the test double), ADR-027.
 *
 * <ul>
 *   <li>The prompt is built from two finished messages and sent with {@code ChatModel.call}: no
 *       ChatClient template rendering, no advisors, no memory and no tools (tool callbacks are
 *       explicitly emptied when the provider's options support tools).</li>
 *   <li>Options start from the provider's configured defaults ({@code getDefaultOptions().mutate()},
 *       so provider-specific settings such as Ollama {@code num-ctx}/{@code seed}/{@code think} apply)
 *       and override temperature, max tokens and, if set, the model.</li>
 *   <li>Structured output: in {@code PROVIDER_SCHEMA} mode (and for repair calls) our own JSON schema is
 *       set as the provider's native output schema when its options implement
 *       {@link StructuredOutputChatOptions}; the raw text is still parsed by the engine.</li>
 *   <li>Each call runs on a virtual thread with a hard deadline; there are no retries here (the
 *       provider retries are switched off in configuration).</li>
 * </ul>
 */
public class SpringAiIngestionLlm implements IngestionLlm, DisposableBean {

    private final ObjectProvider<ChatModel> models;
    private final LlmProperties properties;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("llm-call-", 0).factory());

    public SpringAiIngestionLlm(ObjectProvider<ChatModel> models, LlmProperties properties) {
        this.models = models;
        this.properties = properties;
    }

    private ChatModel chatModel() {
        return properties.enabled() ? models.getIfUnique() : null;
    }

    @Override
    public boolean available() {
        return chatModel() != null;
    }

    @Override
    public String provider() {
        ChatModel m = chatModel();
        return m == null ? "none" : providerName(m);
    }

    @Override
    public String model() {
        ChatModel m = chatModel();
        if (m == null) {
            return null;
        }
        if (properties.model() != null) {
            return properties.model();
        }
        ChatOptions d = m.getDefaultOptions();
        return d == null ? null : d.getModel();
    }

    /** {@code OllamaChatModel} → {@code ollama}, {@code OpenAiChatModel} → {@code openai} ... */
    static String providerName(ChatModel m) {
        String n = m.getClass().getSimpleName();
        if (n.endsWith("ChatModel") && n.length() > "ChatModel".length()) {
            n = n.substring(0, n.length() - "ChatModel".length());
        }
        return n.toLowerCase(Locale.ROOT);
    }

    @Override
    public LlmReply complete(LlmRequest request) {
        ChatModel m = chatModel();
        if (m == null) {
            throw new LlmException(LlmException.Kind.UNAVAILABLE, "no LLM provider is configured", null);
        }
        Prompt prompt = new Prompt(List.of(new SystemMessage(request.system()), new UserMessage(request.user())),
                options(m, request));
        long t0 = System.nanoTime();
        Future<ChatResponse> f = executor.submit(() -> m.call(prompt));
        ChatResponse response;
        try {
            response = f.get(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new LlmException(LlmException.Kind.TIMEOUT, "LLM call " + request.promptId() + " timed out after "
                    + request.timeout().toSeconds() + " s", null);
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.FAILED, "interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (isTimeout(cause)) {
                throw new LlmException(LlmException.Kind.TIMEOUT, "LLM call " + request.promptId() + " timed out",
                        cause);
            }
            throw new LlmException(LlmException.Kind.FAILED, "LLM provider error (" + cause.getClass().getSimpleName()
                    + ")", cause);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? "" : response.getResult().getOutput().getText();
        Usage u = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        String reported = response == null || response.getMetadata() == null ? null : response.getMetadata().getModel();
        return new LlmReply(text, reported == null || reported.isBlank() ? model() : reported,
                u == null ? null : u.getPromptTokens(), u == null ? null : u.getCompletionTokens(), ms);
    }

    ChatOptions options(ChatModel m, LlmRequest request) {
        ChatOptions defaults = m.getDefaultOptions();
        ChatOptions.Builder<?> b = defaults == null ? ChatOptions.builder() : defaults.mutate();
        b.temperature(properties.temperature()).maxTokens(properties.maxOutputTokens());
        if (properties.model() != null) {
            b.model(properties.model());
        }
        if (b instanceof ToolCallingChatOptions.Builder<?> tb) {
            tb.toolCallbacks(List.of());
        }
        boolean schema = request.jsonSchema() != null
                && (request.forceSchema() || properties.structuredOutput() == LlmProperties.StructuredOutput.PROVIDER_SCHEMA);
        if (schema && b instanceof StructuredOutputChatOptions.Builder<?> sb) {
            sb.outputSchema(request.jsonSchema());
        }
        return b.build();
    }

    private static boolean isTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.net.SocketTimeoutException || c instanceof HttpTimeoutException
                    || c instanceof InterruptedIOException || c instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
