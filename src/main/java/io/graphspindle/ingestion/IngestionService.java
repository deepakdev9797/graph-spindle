package io.graphspindle.ingestion;

import io.graphspindle.brain.Brain;
import io.graphspindle.brain.BrainConfig;
import io.graphspindle.brain.BrainService;
import io.graphspindle.common.Actor;
import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Hashing;
import io.graphspindle.common.Ids;
import io.graphspindle.common.Normalizer;
import io.graphspindle.graph.EntityView;
import io.graphspindle.graph.GraphReader;
import io.graphspindle.graph.IngestionRecord;
import io.graphspindle.graph.IngestionStore;
import io.graphspindle.graph.NewIngestion;
import io.graphspindle.graph.SourceInput;
import io.graphspindle.ingestion.pipeline.IngestionPipeline;
import io.graphspindle.ingestion.pipeline.IngestionPipeline.PipelineFailure;
import io.graphspindle.ingestion.pipeline.IngestionPrompts;
import io.graphspindle.ingestion.validation.SecretScanner;
import io.graphspindle.llm.IngestionLlm;
import io.graphspindle.llm.LlmProperties;
import io.graphspindle.vocabulary.BrainVocabulary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * LLM ingestion facade (ARCHITECTURE §3, §6) for REST and, from M8, MCP. Stage 0 (admission) lives
 * here: request limits, secret redaction, content hash, idempotency by {@code requestId} and
 * content-hash deduplication; stages 1–5 run in {@link IngestionPipeline}. The brain id always comes
 * from the caller's path or tool argument, never from content or model output.
 */
@Service
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    public static final Set<String> CONTENT_TYPES = Set.of("CONVERSATION", "NOTE", "OBSERVATION",
            "DOCUMENT_SUMMARY", "OTHER");
    public static final Set<String> SPEAKER_TYPES = Set.of("Person", "Agent");
    static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    static final int MAX_STORED_RESULT_CHARS = 200_000;
    /** Rough prompt overhead in tokens (rules, vocabulary, schema, candidates) besides content and sources. */
    static final int PROMPT_OVERHEAD_TOKENS = 12_000;

    private final BrainService brains;
    private final IngestionStore store;
    private final GraphReader reader;
    private final IngestionPipeline pipeline;
    private final IngestionLlm llm;
    private final LlmProperties llmProperties;
    private final SecretScanner secrets = new SecretScanner();
    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public IngestionService(BrainService brains, IngestionStore store, GraphReader reader, IngestionPipeline pipeline,
            IngestionLlm llm, LlmProperties llmProperties) {
        this.brains = brains;
        this.store = store;
        this.reader = reader;
        this.pipeline = pipeline;
        this.llm = llm;
        this.llmProperties = llmProperties;
    }

    /** Remembers the content (sync). {@code dryRun} returns the plan without persisting anything. */
    public IngestResult ingest(Actor actor, String brainId, IngestRequest request, boolean dryRun, boolean force) {
        BrainScope scope = brains.requireActive(brainId);
        Admitted a = admit(request);
        Brain brain = brains.get(scope.brainId());
        BrainConfig config = brain.config();

        if (!dryRun) {
            if (a.clientRequestId) {
                Optional<IngestResult> replay = replay(scope, a.requestId, a.contentHash);
                if (replay.isPresent()) {
                    return replay.get();
                }
            }
            if (!force && config.dedupWindowHours() > 0) {
                Optional<IngestionRecord> dup = store.findRecentByContentHash(scope, a.contentHash,
                        config.dedupWindowHours());
                if (dup.isPresent()) {
                    return toResult(dup.get()).asDuplicateOf(dup.get().id());
                }
            }
        }
        if (!llm.available()) {
            throw new GraphSpindleException(ErrorCode.LLM_UNAVAILABLE, "no LLM provider is configured or it is"
                    + " disabled; ingestion is unavailable (management and retrieval keep working)");
        }

        BrainVocabulary vocabulary = brains.vocabulary(scope);
        EntityView speaker = resolveSpeaker(scope, brain, a.speaker);
        Instant received = Instant.now();
        String ingestionId = null;
        if (!dryRun) {
            String raw = config.retainRawContent() ? Normalizer.truncate(a.content, config.rawContentMaxChars())
                    : null;
            IngestionRecord rec;
            try {
                rec = store.createReceived(scope, new NewIngestion(a.requestId, a.contentHash, a.contentType,
                        a.content.length(), a.speaker == null ? null : a.speaker.type(),
                        a.speaker == null ? null : a.speaker.name(), actor.id(), request.occurredAt(), raw));
            } catch (GraphSpindleException e) {
                if (e.code() == ErrorCode.IN_PROGRESS && a.clientRequestId) {
                    // lost a race with an identical request: replay it if it has finished meanwhile
                    Optional<IngestResult> replay = replay(scope, a.requestId, a.contentHash);
                    if (replay.isPresent()) {
                        return replay.get();
                    }
                }
                throw e;
            }
            ingestionId = rec.id();
            received = rec.receivedAt() == null ? received : rec.receivedAt();
        }

        IngestionPipeline.Job job = new IngestionPipeline.Job(scope, ingestionId, a.requestId, actor.id(), config,
                vocabulary, a.content, a.contentType, a.sources, speaker, a.speaker, dryRun, received);
        try {
            IngestResult result = pipeline.run(job);
            if (ingestionId != null) {
                storeResult(scope, ingestionId, result);
            }
            return result;
        } catch (PipelineFailure f) {
            log.info("ingestion {} failed: {}", ingestionId, f.code());
            IngestResult failed = new IngestResult(ingestionId, a.requestId, "FAILED", false, null, dryRun, null, 0, 0,
                    0, List.of(), List.of(), List.of(), Map.of(), List.of(), f.llm(),
                    new IngestResult.Error(f.code(), f.getMessage()), received, Instant.now(), true);
            if (ingestionId != null) {
                store.markFailed(scope, ingestionId, report(failed.error()));
                storeResult(scope, ingestionId, failed);
            }
            return failed;
        } catch (RuntimeException e) {
            if (ingestionId != null) {
                IngestResult.Error err = new IngestResult.Error(e instanceof GraphSpindleException g ? g.code().name()
                        : ErrorCode.INTERNAL.name(), e instanceof GraphSpindleException ? e.getMessage()
                                : "internal error while applying the ingestion");
                try {
                    store.markFailed(scope, ingestionId, report(err));
                    storeResult(scope, ingestionId, new IngestResult(ingestionId, a.requestId, "FAILED", false, null,
                            false, null, 0, 0, 0, List.of(), List.of(), List.of(), Map.of(), List.of(), null, err,
                            received, Instant.now(), true));
                } catch (RuntimeException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            if (e instanceof GraphSpindleException) {
                throw e;
            }
            throw new GraphSpindleException(ErrorCode.INTERNAL, "ingestion failed", Map.of(), e);
        }
    }

    public IngestResult get(Actor actor, String brainId, String ingestionId) {
        BrainScope scope = brains.requireActive(brainId);
        if (ingestionId == null || ingestionId.length() > 64) {
            throw GraphSpindleException.notFound("ingestion", ingestionId);
        }
        return store.find(scope, ingestionId).map(this::toResult)
                .orElseThrow(() -> GraphSpindleException.notFound("ingestion", ingestionId));
    }

    public IngestResult getByRequestId(Actor actor, String brainId, String requestId) {
        BrainScope scope = brains.requireActive(brainId);
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw GraphSpindleException.notFound("ingestion", requestId);
        }
        return store.findByRequestId(scope, requestId).map(this::toResult)
                .orElseThrow(() -> GraphSpindleException.notFound("ingestion", requestId));
    }

    /** Provider and model of the active LLM, for system info and the data-transfer notice. */
    public String llmProvider() {
        return llm.provider();
    }

    // ------------------------------------------------------------------ admission

    private record Admitted(String content, String contentType, String requestId, boolean clientRequestId,
            String contentHash, List<SourceInput> sources, IngestionPrompts.Speaker speaker) {
    }

    private Admitted admit(IngestRequest r) {
        if (r == null || r.content() == null || r.content().isBlank()) {
            throw GraphSpindleException.validation("content is required");
        }
        int maxChars = io.graphspindle.common.Limits.defaults().maxContentChars();
        if (r.content().length() > maxChars) {
            throw new GraphSpindleException(ErrorCode.LIMIT_EXCEEDED, "content must be at most " + maxChars
                    + " characters");
        }
        String contentType = r.contentType() == null ? "NOTE" : r.contentType().strip().toUpperCase(java.util.Locale.ROOT);
        if (!CONTENT_TYPES.contains(contentType)) {
            throw GraphSpindleException.validation("contentType must be one of " + CONTENT_TYPES);
        }
        boolean clientId = r.requestId() != null;
        if (clientId && !REQUEST_ID.matcher(r.requestId()).matches()) {
            throw GraphSpindleException.validation("requestId must be 1-100 characters of [A-Za-z0-9._:-]");
        }
        String requestId = clientId ? r.requestId() : "gen-" + Ids.newId();
        int maxSources = io.graphspindle.common.Limits.defaults().maxSources();
        if (r.sources().size() > maxSources) {
            throw new GraphSpindleException(ErrorCode.LIMIT_EXCEEDED, "at most " + maxSources + " sources");
        }
        IngestionPrompts.Speaker speaker = null;
        if (r.speaker() != null) {
            String type = r.speaker().type() == null ? "Person" : r.speaker().type().strip();
            String name = r.speaker().name();
            if (!SPEAKER_TYPES.contains(type)) {
                throw GraphSpindleException.validation("speaker.type must be Person or Agent");
            }
            if (name == null || name.isBlank() || Normalizer.codePointLength(name) > 200
                    || Normalizer.norm(name).isEmpty() || name.strip().startsWith("@")) {
                throw GraphSpindleException.validation("speaker.name is required (at most 200 characters)");
            }
            if (secrets.containsSecret(name)) {
                throw GraphSpindleException.validation("speaker.name looks like a credential");
            }
            speaker = new IngestionPrompts.Speaker(type, name.strip());
        }
        // secrets never reach the LLM or the graph (ARCHITECTURE §3 stage 0); ingestion is not rejected for them
        String content = secrets.redact(r.content());
        List<SourceInput> sources = new ArrayList<>();
        for (IngestRequest.Source s : r.sources()) {
            if (s == null) {
                throw GraphSpindleException.validation("sources must not contain null");
            }
            sources.add(new SourceInput(s.kind(), secrets.redact(s.locator()), secrets.redact(s.title()),
                    secrets.redact(s.summary()), s.mediaType(), s.contentHash()));
        }
        long chars = content.length() + sources.stream().mapToLong(s -> (s.summary() == null ? 0 : s.summary().length())
                + (s.title() == null ? 0 : s.title().length()) + s.locator().length()).sum();
        // content (and sources) appear twice in the reconcile prompt: once as data, once in the extraction
        long estimate = PROMPT_OVERHEAD_TOKENS + chars / 2 + llmProperties.maxOutputTokens();
        if (estimate > llmProperties.contextTokens()) {
            throw new GraphSpindleException(ErrorCode.LIMIT_EXCEEDED, "content and sources do not fit the model"
                    + " context (graphspindle.llm.context-tokens = " + llmProperties.contextTokens() + ")");
        }
        String hash = contentHash(contentType, r.content(), r.sources(), speaker);
        return new Admitted(content, contentType, requestId, clientId, hash, sources, speaker);
    }

    /** ARCHITECTURE §6: sha256(contentType \n normalizeWhitespace(content) \n canonicalJson(sources) \n speaker). */
    String contentHash(String contentType, String content, List<IngestRequest.Source> sources,
            IngestionPrompts.Speaker speaker) {
        var arr = json.createArrayNode();
        for (IngestRequest.Source s : sources) {
            ObjectNode n = arr.addObject(); // fixed key order = canonical
            n.put("kind", s.kind() == null ? null : s.kind().strip().toUpperCase(java.util.Locale.ROOT));
            n.put("locator", s.locator());
            n.put("title", s.title());
            n.put("summary", s.summary());
            n.put("mediaType", s.mediaType());
            n.put("contentHash", s.contentHash());
        }
        String sp = speaker == null ? "" : speaker.type() + ":" + speaker.name();
        return Hashing.sha256Hex(contentType + "\n" + Normalizer.normalizeWhitespace(content) + "\n"
                + json.writeValueAsString(arr) + "\n" + sp);
    }

    /** The entity "I" refers to: the self entity, or the named speaker if it exists in this brain. */
    private EntityView resolveSpeaker(BrainScope scope, Brain brain, IngestionPrompts.Speaker speaker) {
        if (speaker == null) {
            return reader.entity(scope, brain.selfEntityId()).orElseThrow(() -> new GraphSpindleException(
                    ErrorCode.INTERNAL, "the brain has no self entity"));
        }
        String key = Normalizer.canonicalKey(speaker.type(), Normalizer.norm(speaker.name()));
        Optional<EntityView> exact = reader.entityByKey(scope, key);
        if (exact.isPresent()) {
            return reader.resolveEntity(scope, exact.get().id()).filter(e -> "ACTIVE".equals(e.status())).orElse(null);
        }
        return reader.entitiesByAlias(scope, Normalizer.norm(speaker.name())).stream()
                .filter(e -> e.type().equals(speaker.type()) && "ACTIVE".equals(e.status())).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ results

    private Optional<IngestResult> replay(BrainScope scope, String requestId, String contentHash) {
        Optional<IngestionRecord> existing = store.findByRequestId(scope, requestId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        IngestionRecord rec = existing.get();
        if (!rec.contentHash().equals(contentHash)) {
            throw new GraphSpindleException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "requestId was already used for"
                    + " different content", Map.of("requestId", requestId, "ingestionId", rec.id()), null);
        }
        if ("RECEIVED".equals(rec.status())) {
            throw new GraphSpindleException(ErrorCode.IN_PROGRESS, "an ingestion with this requestId is in progress",
                    Map.of("requestId", requestId, "ingestionId", rec.id()), null);
        }
        return Optional.of(toResult(rec).asReplay());
    }

    private IngestResult toResult(IngestionRecord rec) {
        if (rec.result() != null) {
            try {
                return json.readValue(rec.result(), IngestResult.class);
            } catch (RuntimeException e) {
                log.warn("ingestion {}: stored result unreadable; returning the record summary", rec.id());
            }
        }
        IngestResult.Error error = "FAILED".equals(rec.status())
                ? new IngestResult.Error(ErrorCode.INTERNAL.name(), "see report") : null;
        return new IngestResult(rec.id(), rec.requestId(), rec.status(), false, null, false, rec.summary(),
                rec.opsProposed(), rec.opsApplied(), rec.opsRejected(), List.of(), List.of(), List.of(), Map.of(),
                List.of(), rec.llmProvider() == null ? null : new IngestResult.LlmInfo(rec.llmProvider(), rec.llmModel(),
                        rec.promptVersion(), 0, 0, 0, null, null), error, rec.receivedAt(), rec.completedAt(), true);
    }

    private void storeResult(BrainScope scope, String ingestionId, IngestResult result) {
        String s = json.writeValueAsString(result);
        if (s.length() > MAX_STORED_RESULT_CHARS) {
            s = json.writeValueAsString(new IngestResult(result.ingestionId(), result.requestId(), result.status(),
                    false, null, false, result.summary(), result.opsProposed(), result.opsApplied(),
                    result.opsRejected(), List.of(), result.rejected().stream().limit(100).toList(), List.of(),
                    Map.of(), List.of(), result.llm(), result.error(), result.receivedAt(), result.completedAt(),
                    true));
        }
        try {
            store.storeResult(scope, ingestionId, s);
        } catch (RuntimeException e) {
            log.warn("ingestion {}: result could not be stored ({})", ingestionId, e.getClass().getSimpleName());
        }
    }

    private String report(IngestResult.Error error) {
        ObjectNode n = json.createObjectNode();
        n.put("code", error.code());
        n.put("message", Normalizer.truncate(error.message(), 300));
        return json.writeValueAsString(json.createArrayNode().add(n));
    }
}
