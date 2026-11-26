package io.graphspindle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** The provider-neutral LLM gateway (ADR-027), without network. */
class SpringAiIngestionLlmTest {

    static final LlmRequest REQUEST = new LlmRequest("reconcile-v1", "GraphSpindle prompt reconcile-v1.", "hi",
            "{\"type\":\"object\"}", false, Duration.ofSeconds(5));

    /** A ChatModel stub with Ollama-style default options (supports tools and structured output). */
    static final class OllamaLikeChatModel implements ChatModel {
        final AtomicReference<Prompt> last = new AtomicReference<>();
        long delayMs;

        @Override
        public ChatResponse call(Prompt prompt) {
            last.set(prompt);
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"operations\":[]}"))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return OllamaChatOptions.builder().model("gemma4:12b").numCtx(16384).seed(42).temperature(0.7).build();
        }
    }

    static SpringAiIngestionLlm gateway(ChatModel model, LlmProperties props) {
        StaticListableBeanFactory f = new StaticListableBeanFactory();
        if (model != null) {
            f.addBean("chatModel", model);
        }
        return new SpringAiIngestionLlm(f.getBeanProvider(ChatModel.class), props);
    }

    @Test
    void providerDefaultsAreKeptAndEngineOptionsApplied() {
        OllamaLikeChatModel m = new OllamaLikeChatModel();
        LlmReply r = gateway(m, LlmProperties.defaults()).complete(REQUEST);
        assertThat(r.text()).isEqualTo("{\"operations\":[]}");
        ChatOptions o = m.last.get().getOptions();
        assertThat(o).isInstanceOf(OllamaChatOptions.class);
        OllamaChatOptions ollama = (OllamaChatOptions) o;
        assertThat(ollama.getTemperature()).isEqualTo(0.0);
        assertThat(ollama.getNumCtx()).isEqualTo(16384);
        assertThat(ollama.getSeed()).isEqualTo(42);
        assertThat(ollama.getModel()).isEqualTo("gemma4:12b");
        assertThat(ollama.getMaxTokens()).isEqualTo(4096);
        assertThat(((StructuredOutputChatOptions) o).getOutputSchema()).contains("\"type\"");
        assertThat(((ToolCallingChatOptions) o).getToolCallbacks()).isEmpty();
        assertThat(m.last.get().getInstructions()).hasSize(2);
    }

    @Test
    void promptModeSendsNoSchemaExceptForRepairs() {
        OllamaLikeChatModel m = new OllamaLikeChatModel();
        LlmProperties prompt = new LlmProperties(true, "other-model", 0.0, 1024, 8192, null, null,
                LlmProperties.StructuredOutput.PROMPT);
        SpringAiIngestionLlm g = gateway(m, prompt);
        g.complete(REQUEST);
        assertThat(((StructuredOutputChatOptions) m.last.get().getOptions()).getOutputSchema()).isNull();
        assertThat(m.last.get().getOptions().getModel()).isEqualTo("other-model");
        g.complete(new LlmRequest("repair-v1", "s", "u", "{\"type\":\"object\"}", true, Duration.ofSeconds(5)));
        assertThat(((StructuredOutputChatOptions) m.last.get().getOptions()).getOutputSchema()).isNotNull();
    }

    @Test
    void theDeadlineIsEnforced() {
        OllamaLikeChatModel m = new OllamaLikeChatModel();
        m.delayMs = 2000;
        assertThatThrownBy(() -> gateway(m, LlmProperties.defaults()).complete(new LlmRequest("extract-v1", "s", "u",
                null, false, Duration.ofMillis(200)))).isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).kind()).isEqualTo(LlmException.Kind.TIMEOUT));
    }

    @Test
    void noModelOrDisabledMeansUnavailable() {
        SpringAiIngestionLlm none = gateway(null, LlmProperties.defaults());
        assertThat(none.available()).isFalse();
        assertThat(none.provider()).isEqualTo("none");
        assertThatThrownBy(() -> none.complete(REQUEST)).isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).kind()).isEqualTo(LlmException.Kind.UNAVAILABLE));
        LlmProperties off = new LlmProperties(false, null, null, null, null, null, null, null);
        assertThat(gateway(new OllamaLikeChatModel(), off).available()).isFalse();
    }

    @Test
    void providerNamesComeFromTheModelClass() {
        assertThat(SpringAiIngestionLlm.providerName(new FakeChatModel())).isEqualTo("fake");
        assertThat(OllamaChatModel.class.getSimpleName().replace("ChatModel", "").toLowerCase()).isEqualTo("ollama");
    }
}
