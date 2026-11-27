package io.graphspindle.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The provider profiles (M10) select the intended Spring AI chat provider and never contain a key
 * value. That each provider's {@code ChatModel} bean starts without network is ProviderSelectionTest.
 */
class ProviderProfilesTest {

    @ParameterizedTest
    @CsvSource({"ollama,ollama", "openai,openai", "openai-compatible,openai", "anthropic,anthropic", "none,none"})
    void profileSelectsTheProvider(String profile, String chat) throws Exception {
        String text = Files.readString(Path.of("src/main/resources/application-" + profile + ".yml"));
        JsonNode yml = YAMLMapper.builder().build().readTree(text);
        assertThat(yml.at("/spring/ai/model/chat").asString()).isEqualTo(chat);
        assertThat(text).doesNotContainPattern("api-key: [^$]");
    }
}
