package io.graphspindle.ingestion.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.common.Limits;
import io.graphspindle.ingestion.pipeline.PipelineHarness.Mode;
import io.graphspindle.ingestion.pipeline.PipelineHarness.Result;
import io.graphspindle.ingestion.validation.ValidationIssue;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Opt-in live evaluation of the real prompts against a local Ollama (localhost only). Never runs in
 * a normal build:
 *
 * <pre>
 * ./mvnw -B verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=LivePromptEvalIT \
 *     -Dgraphspindle.live-llm=true [-Dgraphspindle.live.models=gemma4:e4b,gemma4:12b] \
 *     [-Dgraphspindle.live.modes=PROMPT,JSON_MODE,PROVIDER_SCHEMA] [-Dgraphspindle.live.round=r1] \
 *     [-Dgraphspindle.live.think=false|true|default] [-Dgraphspindle.live.scenarios=readme-example,...]
 * </pre>
 *
 * For every model, mode and live scenario it runs extract -> reconcile (each with one repair) ->
 * ProposalParser -> OperationValidator against the scenario's hand-built candidate set and writes
 * {@code target/live-eval/<round>.md} (tables) and {@code <round>.jsonl} (raw outputs and issues).
 */
@EnabledIfSystemProperty(named = "graphspindle.live-llm", matches = "true")
class LivePromptEvalIT {

    private static final String BASE_URL = "http://localhost:11434";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void evaluate() throws Exception {
        assertThat(URI.create(BASE_URL).getHost()).isEqualTo("localhost");
        List<String> models = list("graphspindle.live.models", "gemma4:e4b,gemma4:12b");
        List<Mode> modes = list("graphspindle.live.modes", "PROMPT").stream().map(Mode::valueOf).toList();
        String round = System.getProperty("graphspindle.live.round", "r1");
        String think = System.getProperty("graphspindle.live.think", "false");
        int numCtx = Integer.getInteger("graphspindle.live.num-ctx", 16384);
        List<String> only = list("graphspindle.live.scenarios", "");
        List<Scenario> scenarios = Scenario.all().stream().filter(Scenario::live)
                .filter(s -> only.isEmpty() || only.contains(s.name())).toList();

        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        rf.setReadTimeout(Duration.ofSeconds(300));
        OllamaApi api = OllamaApi.builder().baseUrl(BASE_URL).restClientBuilder(RestClient.builder().requestFactory(rf))
                .build();
        RetryTemplate noRetry = new RetryTemplate(RetryPolicy.builder().maxRetries(0).build());

        Path dir = Path.of("target", "live-eval");
        Files.createDirectories(dir);
        StringBuilder md = new StringBuilder("# Live eval " + round + " (think=" + think + ", num_ctx=" + numCtx
                + ")\n\n");
        List<String> jsonl = new ArrayList<>();
        Map<String, int[]> totals = new LinkedHashMap<>();

        for (String model : models) {
            OllamaChatModel chat = OllamaChatModel.builder().ollamaApi(api).retryTemplate(noRetry)
                    .options(OllamaChatOptions.builder().model(model).build()).build();
            // warm-up: load the model so the first scenario's latency is comparable
            chat.call(new org.springframework.ai.chat.prompt.Prompt("Say OK.",
                    OllamaChatOptions.builder().model(model).numPredict(4).thinkOption(ThinkOption.ThinkBoolean.DISABLED)
                            .build()));
            for (Mode mode : modes) {
                PipelineHarness h = new PipelineHarness(chat, m -> options(model, m, think, numCtx), mode,
                        Limits.defaults());
                md.append("## ").append(model).append(" / ").append(mode).append("\n\n")
                        .append("| scenario | extract ok | parse ok | repairs | ops | accepted | rejected | codes | "
                                + "latency ms (calls) | prompt tok |\n|---|---|---|---|---|---|---|---|---|---|\n");
                int[] t = totals.computeIfAbsent(model + " / " + mode, k -> new int[6]);
                for (Scenario s : scenarios) {
                    Result r = h.run(s);
                    String lat = r.totalMillis() + " (" + String.join("+", r.calls().stream()
                            .map(c -> String.valueOf(c.millis())).toList()) + ")";
                    String tok = String.join("+", r.calls().stream()
                            .map(c -> String.valueOf(c.promptTokens())).toList());
                    md.append("| ").append(s.name()).append(" | ").append(r.extractionParsed() ? "yes" : "NO")
                            .append(" | ").append(r.proposalParsed() ? "yes" : "NO").append(" | ")
                            .append(r.repairs()).append(" | ").append(r.wireOps()).append(" | ")
                            .append(r.acceptedOps()).append(" | ").append(r.rejectedOps()).append(" | ")
                            .append(r.failure() != null ? r.failure().replace('|', '/') : r.rejectionCodes())
                            .append(" | ").append(lat).append(" | ").append(tok).append(" |\n");
                    t[0]++;
                    t[1] += r.proposalParsed() ? 1 : 0;
                    t[2] += r.wireOps();
                    t[3] += r.acceptedOps();
                    t[4] += r.repairs();
                    t[5] += (int) r.totalMillis();
                    jsonl.add(record(round, model, mode, s, r));
                    System.out.println("LIVE " + round + " " + model + " " + mode + " " + s.name() + " -> parsed="
                            + r.proposalParsed() + " ops=" + r.wireOps() + " accepted=" + r.acceptedOps() + " "
                            + r.rejectionCodes() + " " + r.totalMillis() + " ms");
                }
                md.append('\n');
            }
        }
        md.append("## Totals\n\n| model / mode | scenarios | parsed | ops | accepted | acceptance | repairs | "
                + "total s |\n|---|---|---|---|---|---|---|---|\n");
        totals.forEach((k, t) -> md.append("| ").append(k).append(" | ").append(t[0]).append(" | ").append(t[1])
                .append(" | ").append(t[2]).append(" | ").append(t[3]).append(" | ")
                .append(t[2] == 0 ? "-" : String.format("%.0f%%", 100.0 * t[3] / t[2])).append(" | ").append(t[4])
                .append(" | ").append(t[5] / 1000).append(" |\n"));
        Files.writeString(dir.resolve(round + ".md"), md, StandardCharsets.UTF_8);
        Files.write(dir.resolve(round + ".jsonl"), jsonl, StandardCharsets.UTF_8);
        System.out.println(md);
    }

    private static ChatOptions.Builder<?> options(String model, Mode mode, String think, int numCtx) {
        OllamaChatOptions.Builder b = OllamaChatOptions.builder().model(model).temperature(0.0).seed(42)
                .numCtx(numCtx).numPredict(8192);
        if ("false".equals(think)) {
            b.thinkOption(ThinkOption.ThinkBoolean.DISABLED);
        } else if ("true".equals(think)) {
            b.thinkOption(ThinkOption.ThinkBoolean.ENABLED);
        }
        if (mode == Mode.JSON_MODE) {
            b.format("json");
        }
        return b;
    }

    private static String record(String round, String model, Mode mode, Scenario s, Result r) {
        ObjectNode n = JSON.createObjectNode();
        n.put("round", round);
        n.put("model", model);
        n.put("mode", mode.name());
        n.put("scenario", s.name());
        n.put("failure", r.failure());
        n.put("ops", r.wireOps());
        n.put("accepted", r.acceptedOps());
        var calls = n.putArray("calls");
        r.calls().forEach(c -> {
            ObjectNode cn = calls.addObject();
            cn.put("prompt", c.promptId());
            cn.put("ms", c.millis());
            cn.put("promptTokens", c.promptTokens());
            cn.put("completionTokens", c.completionTokens());
            cn.put("error", c.error());
            cn.put("raw", c.raw());
        });
        var issues = n.putArray("rejected");
        if (r.plan() != null) {
            for (ValidationIssue i : r.plan().rejected()) {
                issues.addObject().put("op", i.opIndex()).put("name", i.op()).put("code", i.code().name())
                        .put("message", i.message());
            }
            var adj = n.putArray("adjusted");
            r.plan().adjusted().forEach(a -> adj.add(a.kind() + " op" + a.opIndex()));
        }
        return JSON.writeValueAsString(n);
    }

    private static List<String> list(String prop, String def) {
        String v = System.getProperty(prop, def);
        return v.isBlank() ? List.of() : Arrays.stream(v.split(",")).map(String::strip).toList();
    }
}
