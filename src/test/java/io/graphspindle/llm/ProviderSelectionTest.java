package io.graphspindle.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * {@code spring.ai.model.chat} selects exactly one provider (ADR-027); contexts start with dummy or no
 * credentials and make no network call (clients are lazy). Runs without Neo4j.
 */
class ProviderSelectionTest {

    static final String NO_DB = "management.health.neo4j.enabled=false";
    static final String NO_SCHEMA = "graphspindle.graph.initialize-schema=false";

    abstract static class Base {
        @Autowired
        ApplicationContext context;
        @Autowired
        IngestionLlm llm;
        @Autowired
        io.graphspindle.admin.SystemInfoService systemInfo;

        /** M10 ProviderSelectionIT: system info reports provider, host and the data transfer. */
        void expectInfo(String host, boolean leaves, String structuredOutput) {
            var info = systemInfo.info(io.graphspindle.common.Actor.LOCAL_ADMIN);
            assertThat(info.llm().host()).isEqualTo(host);
            assertThat(info.dataLeavesMachine()).isEqualTo(leaves);
            assertThat(info.llm().structuredOutput()).isEqualTo(structuredOutput);
        }

        void expect(String provider, String model) {
            assertThat(context.getBeansOfType(ChatModel.class)).hasSize(provider.equals("none") ? 0 : 1);
            assertThat(llm.provider()).isEqualTo(provider);
            assertThat(llm.available()).isEqualTo(!provider.equals("none"));
            if (model != null) {
                assertThat(llm.model()).isEqualTo(model);
            }
        }
    }

    @Nested
    @SpringBootTest(properties = {NO_DB, NO_SCHEMA, "spring.ai.model.chat=ollama"})
    class Ollama extends Base {
        @Test
        void ollamaIsTheLocalDefault() {
            expect("ollama", "gemma4:12b");
            expectInfo("localhost", false, "AUTO:PROVIDER_SCHEMA");
        }
    }

    @Nested
    @SpringBootTest(properties = {NO_DB, NO_SCHEMA, "spring.ai.model.chat=openai",
            "spring.ai.openai.base-url=http://localhost:1234/v1", "spring.ai.openai.api-key=dummy",
            "spring.ai.openai.chat.model=local-model"})
    class OpenAiCompatible extends Base {
        @Test
        void openAiCompatibleServerByBaseUrl() {
            expect("openai", "local-model");
            expectInfo("localhost", false, "AUTO:PROMPT");
        }
    }

    @Nested
    @SpringBootTest(properties = {NO_DB, NO_SCHEMA, "spring.ai.model.chat=anthropic",
            "spring.ai.anthropic.api-key=dummy"})
    class Anthropic extends Base {
        @Test
        void anthropic() {
            expect("anthropic", null);
            expectInfo("api.anthropic.com", true, "AUTO:PROMPT");
        }
    }

    @Nested
    @SpringBootTest(properties = {NO_DB, NO_SCHEMA})
    class None extends Base {
        @Test
        void noneByDefault() {
            expect("none", null);
            expectInfo(null, false, "AUTO");
        }
    }
}
