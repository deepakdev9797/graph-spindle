package io.graphspindle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.client.ResourceAccessException;

class FakeChatModelTest {

    private static String user(String content) {
        DataBlocks b = DataBlocks.forTexts(java.util.List.of(content));
        return "Message:\n" + b.wrap("content", content);
    }

    private static String ask(FakeChatModel fake, String stage, String content) {
        return ChatClient.create(fake).prompt().system("GraphSpindle prompt " + stage + ".\nrules").user(user(content))
                .call().content();
    }

    @Test
    void routesByStageAndContentHashAndRecordsPrompts() {
        FakeChatModel fake = new FakeChatModel()
                .script("a", "Alice likes tea.", "extract-v1", "{\"raw\":\"E1\"}")
                .script("a", "Alice likes tea.", "reconcile-v1", "{\"json\":{\"operations\":[]}}", "{\"raw\":\"R2\"}");
        assertThat(ask(fake, "reconcile-v1", "Alice   likes tea.")).isEqualTo("{\"operations\":[]}");
        assertThat(ask(fake, "extract-v1", "Alice likes tea.")).isEqualTo("E1");
        assertThat(ask(fake, "reconcile-v1", "Alice likes tea.")).isEqualTo("R2");
        assertThat(fake.calls()).extracting(FakeChatModel.Call::stage)
                .containsExactly("reconcile-v1", "extract-v1", "reconcile-v1");
        assertThat(fake.calls().get(0).user()).contains("Alice   likes tea.");
        assertThatThrownBy(() -> ask(fake, "reconcile-v1", "Alice likes tea.")).hasMessageContaining("no (more)");
        assertThatThrownBy(() -> ask(fake, "reconcile-v1", "unknown")).hasMessageContaining("no script");
    }

    @Test
    void markerRoutingAndRepairFollowTheLastScenario() {
        FakeChatModel fake = new FakeChatModel()
                .script("m", "[[fake-llm:m]]", "reconcile-v1", "{\"raw\":\"{broken\"}")
                .script("m", "[[fake-llm:m]]", "repair-v1", "{\"json\":{\"operations\":[]}}");
        assertThat(ask(fake, "reconcile-v1", "any text [[fake-llm:m]] here")).isEqualTo("{broken");
        String repaired = ChatClient.create(fake).prompt().system("GraphSpindle prompt repair-v1.").user("previous")
                .call().content();
        assertThat(repaired).isEqualTo("{\"operations\":[]}");
    }

    @Test
    void simulatesTimeoutsFailuresAndOversizeOutput() {
        FakeChatModel fake = new FakeChatModel()
                .script("t", "slow", "extract-v1", "{\"error\":\"timeout\"}", "{\"error\":\"runtime\"}",
                        "{\"oversizeChars\":3000000}", "{\"raw\":\"late\",\"delayMs\":50}");
        assertThatThrownBy(() -> ask(fake, "extract-v1", "slow")).isInstanceOf(ResourceAccessException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class);
        assertThatThrownBy(() -> ask(fake, "extract-v1", "slow")).isInstanceOf(IllegalStateException.class);
        assertThat(ask(fake, "extract-v1", "slow")).hasSizeGreaterThanOrEqualTo(3_000_000).startsWith("{\"summary\"");
        long t0 = System.nanoTime();
        assertThat(ask(fake, "extract-v1", "slow")).isEqualTo("late");
        assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(50);
    }

    @Test
    void refusesPromptsWithoutIdOrContentBlock() {
        FakeChatModel fake = FakeChatModel.fromResources();
        assertThatThrownBy(() -> ChatClient.create(fake).prompt().system("hello").user("x").call().content())
                .hasMessageContaining("prompt id");
        assertThatThrownBy(() -> ChatClient.create(fake).prompt().system("GraphSpindle prompt extract-v1.")
                .user("no block").call().content()).hasMessageContaining("content block");
    }
}
