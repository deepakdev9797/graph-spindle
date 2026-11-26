package io.graphspindle.ingestion.pipeline;

import io.graphspindle.common.Limits;
import io.graphspindle.ingestion.ops.MalformedModelOutputException;
import io.graphspindle.ingestion.ops.ParsedProposal;
import io.graphspindle.ingestion.ops.ProposalParser;
import io.graphspindle.ingestion.pipeline.IngestionPrompts.RenderedPrompt;
import io.graphspindle.ingestion.validation.OperationValidator;
import io.graphspindle.ingestion.validation.ValidatedPlan;
import io.graphspindle.ingestion.validation.ValidationIssue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.StructuredOutputConverter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A test-scope stand-in for the M6 pipeline LLM stages: extract (stage 1, with one repair), reconcile
 * (stage 3, with one repair), parse ({@link ProposalParser}) and validate ({@link OperationValidator})
 * against the scenario's hand-built candidate set. Stage 2 (candidate lookup) and stage 5 (apply)
 * are out of scope here. Used by the fake-LLM scenario tests and by the live eval.
 *
 * <p>The ChatClient is built from the bare ChatModel: no tools, no advisors, no memory. Prompt text
 * is passed without template parameters, so Spring AI never runs a template renderer over it.
 */
public final class PipelineHarness {

    /** How the JSON answer is requested from the provider. */
    public enum Mode {
        /** Schema only in the prompt; {@code call().content()}-style raw text. */
        PROMPT,
        /** Provider JSON mode (Ollama {@code format: "json"}), set by the options factory. */
        JSON_MODE,
        /** {@code entity(converter, s -> s.useProviderStructuredOutput())}: the schema goes to the provider. */
        PROVIDER_SCHEMA
    }

    public record Call(String promptId, String raw, long millis, Integer promptTokens, Integer completionTokens,
            String error) {
    }

    public record Result(String scenario, List<Call> calls, boolean extractionParsed, boolean proposalParsed,
            int repairs, ParsedProposal parsed, ValidatedPlan plan, String failure) {

        public int wireOps() {
            return parsed == null ? 0 : parsed.wireOperationCount();
        }

        public int rejectedOps() {
            return plan == null ? 0 : (int) plan.rejected().stream().mapToInt(ValidationIssue::opIndex)
                    .filter(i -> i >= 0).distinct().count();
        }

        public int acceptedOps() {
            return wireOps() - rejectedOps();
        }

        public Map<String, Integer> rejectionCodes() {
            Map<String, Integer> out = new TreeMap<>();
            if (plan != null) {
                plan.rejected().forEach(r -> out.merge(r.code().name(), 1, Integer::sum));
            }
            return out;
        }

        public long totalMillis() {
            return calls.stream().mapToLong(Call::millis).sum();
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ChatClient client;
    private final Function<Mode, ChatOptions.Builder<?>> options;
    private final Mode mode;
    private final Limits limits;
    private final IngestionPrompts prompts = new IngestionPrompts();
    private final ProposalParser parser;
    private final OperationValidator validator = new OperationValidator();

    public PipelineHarness(ChatModel model, Function<Mode, ChatOptions.Builder<?>> options, Mode mode, Limits limits) {
        this.client = ChatClient.builder(model).build();
        this.options = options;
        this.mode = mode;
        this.limits = limits;
        this.parser = new ProposalParser(limits);
    }

    public static PipelineHarness withDefaults(ChatModel model) {
        return new PipelineHarness(model, m -> ChatOptions.builder().temperature(0.0), Mode.PROMPT,
                Limits.defaults());
    }

    public IngestionPrompts prompts() {
        return prompts;
    }

    public Result run(Scenario s) {
        List<Call> calls = new ArrayList<>();
        int repairs = 0;

        // stage 1: extract (one repair; on a second failure continue with an empty extraction)
        RenderedPrompt extract = prompts.extract(s.input(limits, null));
        Call c = call(extract, prompts.extractionSchema(Scenario.CORE, limits));
        calls.add(c);
        if (c.error() != null) {
            return new Result(s.name(), calls, false, false, repairs, null, null, "LLM_ERROR: " + c.error());
        }
        String extraction = extractionJson(c.raw());
        if (extraction == null) {
            repairs++;
            RenderedPrompt repair = prompts.repair(IngestionPrompts.EXTRACT, Scenario.CORE, limits, c.raw(),
                    prompts.syntaxHint(c.raw(), "not a JSON object with mentions and statements"));
            Call r = call(repair, prompts.extractionSchema(Scenario.CORE, limits));
            calls.add(r);
            extraction = r.error() == null ? extractionJson(r.raw()) : null;
        }
        boolean extractionParsed = extraction != null;

        // stage 3: reconcile (one repair; a second failure is MALFORMED_MODEL_OUTPUT)
        RenderedPrompt reconcile = prompts.reconcile(s.input(limits, extractionParsed ? extraction : "{}"));
        String proposalSchema = prompts.proposalSchema(Scenario.CORE, limits);
        c = call(reconcile, proposalSchema);
        calls.add(c);
        if (c.error() != null) {
            return new Result(s.name(), calls, extractionParsed, false, repairs, null, null, "LLM_ERROR: " + c.error());
        }
        ParsedProposal parsed;
        try {
            parsed = parser.parse(c.raw());
        } catch (MalformedModelOutputException first) {
            repairs++;
            RenderedPrompt repair = prompts.repair(IngestionPrompts.RECONCILE, Scenario.CORE, limits, c.raw(),
                    prompts.syntaxHint(c.raw(), first.getMessage()));
            Call r = call(repair, proposalSchema);
            calls.add(r);
            if (r.error() != null) {
                return new Result(s.name(), calls, extractionParsed, false, repairs, null, null,
                        "LLM_ERROR: " + r.error());
            }
            try {
                parsed = parser.parse(r.raw());
            } catch (MalformedModelOutputException second) {
                return new Result(s.name(), calls, extractionParsed, false, repairs, null, null,
                        "MALFORMED_MODEL_OUTPUT: " + second.getMessage());
            }
        }
        ValidatedPlan plan = validator.validate(parsed, s.validationContext().withLimits(limits));
        return new Result(s.name(), calls, extractionParsed, true, repairs, parsed, plan, null);
    }

    private Call call(RenderedPrompt p, String schema) {
        long t0 = System.nanoTime();
        // A repair at temperature 0 tends to reproduce the same syntax error verbatim, so repairs
        // always use at least the provider's JSON mode (grammar-constrained syntax).
        Mode effective = mode == Mode.PROMPT && IngestionPrompts.REPAIR.equals(p.promptId()) ? Mode.JSON_MODE : mode;
        try {
            var spec = client.prompt().system(p.system()).user(p.user()).options(options.apply(effective));
            ChatResponse response;
            String raw;
            if (effective == Mode.PROVIDER_SCHEMA) {
                ResponseEntity<ChatResponse, String> re = spec.call()
                        .responseEntity(new RawJsonConverter(schema), e -> e.useProviderStructuredOutput());
                response = re.response();
                raw = re.entity();
            } else {
                response = spec.call().chatResponse();
                raw = response == null || response.getResult() == null ? null
                        : response.getResult().getOutput().getText();
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Usage u = response == null ? null : response.getMetadata().getUsage();
            return new Call(p.promptId(), raw == null ? "" : raw, ms, u == null ? null : u.getPromptTokens(),
                    u == null ? null : u.getCompletionTokens(), null);
        } catch (RuntimeException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return new Call(p.promptId(), null, ms, null, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Lenient stage-1 parse: the outermost JSON object with a statements array, re-serialized compactly. */
    static String extractionJson(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(raw.substring(start, end + 1));
            if (!n.isObject() || !n.path("statements").isArray()) {
                return null;
            }
            return JSON.writeValueAsString(n);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Hands the provider our own JSON schema for native structured output and returns the raw text,
     * so {@link ProposalParser} stays the single source of truth (ARCHITECTURE §4.3).
     */
    static final class RawJsonConverter implements StructuredOutputConverter<String> {

        private final String schema;

        RawJsonConverter(String schema) {
            this.schema = schema;
        }

        @Override
        public String convert(String source) {
            return source;
        }

        @Override
        public String getFormat() {
            return ""; // the schema is already in the system prompt
        }

        @Override
        public String getJsonSchema() {
            return schema;
        }
    }
}
