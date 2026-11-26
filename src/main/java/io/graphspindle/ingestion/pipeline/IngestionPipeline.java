package io.graphspindle.ingestion.pipeline;

import io.graphspindle.brain.BrainConfig;
import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.Limits;
import io.graphspindle.common.Normalizer;
import io.graphspindle.common.Origin;
import io.graphspindle.graph.ApplyRequest;
import io.graphspindle.graph.ApplyResult;
import io.graphspindle.graph.EntityView;
import io.graphspindle.graph.GraphStore;
import io.graphspindle.graph.IngestionCompletion;
import io.graphspindle.graph.SourceInput;
import io.graphspindle.ingestion.IngestResult;
import io.graphspindle.ingestion.IngestionProperties;
import io.graphspindle.ingestion.ops.MalformedModelOutputException;
import io.graphspindle.ingestion.ops.ParsedProposal;
import io.graphspindle.ingestion.ops.ProposalParser;
import io.graphspindle.ingestion.pipeline.IngestionPrompts.PromptSource;
import io.graphspindle.ingestion.pipeline.IngestionPrompts.RenderedPrompt;
import io.graphspindle.ingestion.pipeline.IngestionPrompts.Speaker;
import io.graphspindle.ingestion.validation.OperationValidator;
import io.graphspindle.ingestion.validation.ResolvedEntity;
import io.graphspindle.ingestion.validation.SecretScanner;
import io.graphspindle.ingestion.validation.ValidatedPlan;
import io.graphspindle.ingestion.validation.ValidationContext;
import io.graphspindle.ingestion.validation.ValidationIssue;
import io.graphspindle.llm.IngestionLlm;
import io.graphspindle.llm.LlmException;
import io.graphspindle.llm.LlmProperties;
import io.graphspindle.llm.LlmReply;
import io.graphspindle.llm.LlmRequest;
import io.graphspindle.llm.TokenEstimator;
import io.graphspindle.vocabulary.BrainVocabulary;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stages 1–5 of an admitted ingestion (ARCHITECTURE §3): extract (LLM) → candidates (this brain's
 * graph) → reconcile (LLM) → parse (one repair) → engine transforms (ADR-028) → validate → duplicate
 * suspicion (ADR-030) → apply.
 * Synchronous within a total time budget; every stage is a plain method call on immutable inputs, so
 * an asynchronous runner can call {@link #run} later unchanged.
 */
@Component
public class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);
    static final int MAX_REPORT_CHARS = 32_000;
    static final int MAX_MESSAGE_CHARS = 300;

    private final IngestionLlm llm;
    private final LlmProperties llmProperties;
    private final CandidateStage candidateStage;
    private final GraphStore graph;
    private final IngestionPrompts prompts;
    private final OperationValidator validator = new OperationValidator();
    private final CorrectionCues cues;
    private final JsonMapper json = JsonMapper.builder().build();

    public IngestionPipeline(IngestionLlm llm, LlmProperties llmProperties, CandidateStage candidateStage,
            GraphStore graph, IngestionProperties properties) {
        this.llm = llm;
        this.llmProperties = llmProperties;
        this.candidateStage = candidateStage;
        this.graph = graph;
        this.cues = new CorrectionCues(properties.correctionCues());
        this.prompts = new IngestionPrompts(candidateTokens(llmProperties), extractionTokens(llmProperties));
    }

    /** A quarter of the context for the candidates block (ADR-031), within [1k, 16k] estimated tokens. */
    static int candidateTokens(LlmProperties p) {
        return Math.clamp(p.contextTokens() / 4, 1_000, 16_000);
    }

    /** A tenth of the context for the extraction hint (ADR-031), within [500, 6k] estimated tokens. */
    static int extractionTokens(LlmProperties p) {
        return Math.clamp(p.contextTokens() / 10, 500, 6_000);
    }

    /**
     * An admitted ingestion.
     *
     * @param ingestionId the RECEIVED Ingestion record, or null for a dry run
     * @param content the redacted content (what the LLM and the evidence checker see)
     * @param sources the request sources (redacted text fields)
     * @param speaker the entity "I" refers to; null when {@code namedSpeaker} is new in this brain
     * @param namedSpeaker the request's speaker, or null for the brain owner
     */
    public record Job(BrainScope scope, String ingestionId, String requestId, String principal, BrainConfig config,
            BrainVocabulary vocabulary, String content, String contentType, List<SourceInput> sources,
            EntityView speaker, Speaker namedSpeaker, boolean dryRun, Instant receivedAt) {
    }

    /** The ingestion could not complete; nothing was written. */
    public static final class PipelineFailure extends RuntimeException {

        private final String code;
        private final transient IngestResult.LlmInfo llm;

        PipelineFailure(String code, String message, IngestResult.LlmInfo llm, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.llm = llm;
        }

        public String code() {
            return code;
        }

        public IngestResult.LlmInfo llm() {
            return llm;
        }
    }

    /** Calls made so far, for provenance. */
    private final class Calls {
        final Instant deadline = Instant.now().plus(llmProperties.timeout());
        int calls;
        int repairs;
        long millis;
        Integer promptTokens;
        Integer completionTokens;
        String model;

        LlmReply call(RenderedPrompt p, String schema, boolean repair) {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                throw new LlmException(LlmException.Kind.TIMEOUT, "ingestion LLM time budget of "
                        + llmProperties.timeout().toSeconds() + " s exhausted", null);
            }
            Duration timeout = remaining.compareTo(llmProperties.callTimeout()) < 0 ? remaining
                    : llmProperties.callTimeout();
            LlmRequest request = new LlmRequest(p.promptId(), p.system(), p.user(), schema, repair, timeout);
            // never send a prompt that cannot fit: providers truncate silently (ADR-031)
            long estimate = TokenEstimator.estimate(request) + llmProperties.maxOutputTokens();
            if (estimate > llmProperties.contextTokens()) {
                throw new PipelineFailure(ErrorCode.LIMIT_EXCEEDED.name(), "the " + p.promptId() + " prompt (about "
                        + (estimate - llmProperties.maxOutputTokens()) + " tokens plus " + llmProperties.maxOutputTokens()
                        + " output tokens) does not fit the model context (graphspindle.llm.context-tokens = "
                        + llmProperties.contextTokens() + "); nothing was sent", info(), null);
            }
            calls++;
            if (repair) {
                repairs++;
            }
            LlmReply r = llm.complete(request);
            millis += r.millis();
            promptTokens = add(promptTokens, r.promptTokens());
            completionTokens = add(completionTokens, r.completionTokens());
            model = r.model() == null ? model : r.model();
            return r;
        }

        IngestResult.LlmInfo info() {
            return new IngestResult.LlmInfo(llm.provider(), model == null ? llm.model() : model,
                    IngestionPrompts.RECONCILE, calls, repairs, millis, promptTokens, completionTokens);
        }

        private static Integer add(Integer a, Integer b) {
            return a == null ? b : b == null ? a : Integer.valueOf(a + b);
        }
    }

    public IngestResult run(Job job) {
        Calls calls = new Calls();
        try {
            return stages(job, calls);
        } catch (LlmException e) {
            throw new PipelineFailure("LLM_UNAVAILABLE", e.getMessage(), calls.info(), e);
        }
    }

    private IngestResult stages(Job job, Calls calls) {
        BrainVocabulary vocab = job.vocabulary();
        Limits limits = job.config().limits().toLimits();
        List<PromptSource> promptSources = job.sources().stream()
                .map(s -> new PromptSource(s.kind(), s.locator(), s.title(), s.summary())).toList();

        // 1. extract (one repair; an unusable extraction degrades to an empty hint)
        RenderedPrompt extractPrompt = prompts.extract(new IngestionPrompts.Input(vocab, limits, job.content(),
                job.contentType(), job.namedSpeaker(), promptSources, List.of(), List.of(), null));
        String extractionSchema = prompts.extractionSchema(vocab, limits);
        LlmReply first = calls.call(extractPrompt, extractionSchema, false);
        Extraction extraction = Extraction.parse(first.text());
        if (extraction == null) {
            RenderedPrompt repair = prompts.repair(IngestionPrompts.EXTRACT, vocab, limits, first.text(),
                    prompts.syntaxHint(first.text(), "not a JSON object with mentions and statements"));
            extraction = Extraction.parse(calls.call(repair, extractionSchema, true).text());
        }
        if (extraction == null) {
            log.debug("ingestion {}: extraction unusable after repair; continuing without hints", job.ingestionId());
            extraction = Extraction.empty();
        }

        // 2. candidates (this brain only)
        String speakerLabel = job.namedSpeaker() == null ? "the speaker (brain owner)"
                : job.namedSpeaker().name();
        CandidateStage.Candidates candidates = candidateStage.find(job.scope(), vocab, extraction, job.speaker(),
                speakerLabel);

        // 3. reconcile (one repair; a second failure is MALFORMED_MODEL_OUTPUT)
        RenderedPrompt reconcilePrompt = prompts.reconcile(new IngestionPrompts.Input(vocab, limits, job.content(),
                job.contentType(), job.namedSpeaker(), promptSources, candidates.entities(), candidates.claims(),
                extraction.json()));
        String proposalSchema = prompts.proposalSchema(vocab, limits);
        ProposalParser parser = new ProposalParser(limits);
        LlmReply answer = calls.call(reconcilePrompt, proposalSchema, false);
        ParsedProposal parsed;
        try {
            parsed = parser.parse(answer.text(), vocab.attributeNames());
        } catch (MalformedModelOutputException firstError) {
            RenderedPrompt repair = prompts.repair(IngestionPrompts.RECONCILE, vocab, limits, answer.text(),
                    firstError.getMessage());
            LlmReply repaired = calls.call(repair, proposalSchema, true);
            try {
                parsed = parser.parse(repaired.text(), vocab.attributeNames());
            } catch (MalformedModelOutputException second) {
                throw new PipelineFailure("MALFORMED_MODEL_OUTPUT", "model output could not be parsed after one repair: "
                        + Normalizer.truncate(second.getMessage(), MAX_MESSAGE_CHARS), calls.info(), second);
            }
        }

        // counted before the engine transforms, which may drop operations (ADR-030)
        int proposed = parsed.wireOperationCount();

        // engine transforms (ADR-028)
        if (job.speaker() == null && job.namedSpeaker() != null) {
            parsed = ProposalTransforms.createSpeaker(parsed, job.namedSpeaker().type(), job.namedSpeaker().name());
        }
        parsed = ProposalTransforms.downgradeUnsupportedCorrections(parsed, cues, job.content());

        // 4. validate
        List<String> summaries = job.sources().stream().map(SourceInput::summary).toList();
        ValidationContext ctx = new ValidationContext(job.scope().brainId(), vocab, candidates.set(), job.content(),
                summaries, limits, job.config().validationPolicy(), new SecretScanner());
        ValidatedPlan plan = validator.validate(parsed, ctx);
        // the engine flags near-duplicate new entities itself (SCHEMA §10.5, ADR-030)
        plan = DuplicateSuspicion.addSuspectedDuplicates(plan, candidates.set(), vocab,
                job.config().duplicateSuspicionScore());
        int rejectedOps = (int) plan.rejected().stream().mapToInt(ValidationIssue::opIndex).filter(i -> i >= 0)
                .distinct().count();
        IngestResult.LlmInfo info = calls.info();

        if (job.dryRun()) {
            List<IngestResult.Planned> planned = plan.commands().stream().map(c -> new IngestResult.Planned(
                    c.opIndex(), c.getClass().getSimpleName().replace("Command", ""),
                    Normalizer.truncate(c.toString(), MAX_MESSAGE_CHARS))).toList();
            return new IngestResult(null, job.requestId(), "DRY_RUN", false, null, true, plan.summary(), proposed, 0,
                    rejectedOps, List.of(), rejected(plan), adjusted(plan), refs(plan), planned, info, null,
                    job.receivedAt(), Instant.now(), true);
        }

        // 5. apply (one transaction)
        IngestionCompletion completion = new IngestionCompletion(plan.summary(), proposed, rejectedOps, report(plan),
                info.provider(), info.model(), IngestionPrompts.RECONCILE);
        ApplyResult applied = graph.apply(job.scope(), new ApplyRequest(job.ingestionId(), job.principal(), Origin.LLM,
                job.sources(), plan, vocab, job.config().conflictPolicy(), job.config().allowLlmSupersession(),
                completion));
        List<IngestResult.Outcome> outcomes = applied.outcomes().stream().map(o -> new IngestResult.Outcome(
                o.opIndex(), o.outcome().name(), o.entityId(), o.claimId(), o.conflictId())).toList();
        Map<String, String> refs = new LinkedHashMap<>(applied.refs());
        refs.remove(OperationValidator.SPEAKER_REF);
        return new IngestResult(job.ingestionId(), job.requestId(), applied.status(), false, null, false,
                plan.summary(), proposed, applied.opsApplied(), rejectedOps, outcomes, rejected(plan), adjusted(plan),
                refs, List.of(), info, null, job.receivedAt(), Instant.now(), true);
    }

    private static List<IngestResult.Rejected> rejected(ValidatedPlan plan) {
        return plan.rejected().stream().map(r -> new IngestResult.Rejected(r.opIndex(), r.op(), r.code().name(),
                Normalizer.truncate(r.message(), MAX_MESSAGE_CHARS))).toList();
    }

    private static List<IngestResult.Adjusted> adjusted(ValidatedPlan plan) {
        return plan.adjusted().stream().map(a -> new IngestResult.Adjusted(a.opIndex(), a.op(), a.kind().name(),
                Normalizer.truncate(a.message(), MAX_MESSAGE_CHARS))).toList();
    }

    private static Map<String, String> refs(ValidatedPlan plan) {
        Map<String, String> out = new LinkedHashMap<>();
        plan.refs().forEach((ref, e) -> {
            if (!OperationValidator.SPEAKER_REF.equals(ref)) {
                out.put(ref, e instanceof ResolvedEntity.Existing x ? x.entityId() : "new:"
                        + ((ResolvedEntity.New) e).canonicalKey());
            }
        });
        return out;
    }

    /** JSON array of rejected operations, bounded (SCHEMA §5.4 {@code report}). */
    String report(ValidatedPlan plan) {
        ArrayNode a = json.createArrayNode();
        int size = 2;
        for (ValidationIssue r : plan.rejected()) {
            ObjectNode n = json.createObjectNode();
            n.put("opIndex", r.opIndex());
            n.put("op", r.op());
            n.put("code", r.code().name());
            n.put("message", Normalizer.truncate(r.message(), MAX_MESSAGE_CHARS));
            String s = json.writeValueAsString(n);
            if (size + s.length() + 1 > MAX_REPORT_CHARS) {
                break;
            }
            size += s.length() + 1;
            a.add(n);
        }
        return json.writeValueAsString(a);
    }
}
