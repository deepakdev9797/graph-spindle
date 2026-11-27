package io.graphspindle.ingestion.pipeline;

import io.graphspindle.common.Limits;
import io.graphspindle.common.Normalizer;
import io.graphspindle.llm.DataBlocks;
import io.graphspindle.llm.PromptTemplate;
import io.graphspindle.llm.TokenEstimator;
import io.graphspindle.vocabulary.BrainVocabulary;
import io.graphspindle.vocabulary.OutputSchemaRenderer;
import io.graphspindle.vocabulary.PromptVocabularyRenderer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the stage-1 (extract), stage-3 (reconcile) and repair prompts (ARCHITECTURE §3, §4.3).
 *
 * <p>The system message holds only engine-controlled text: the rules, the brain vocabulary
 * ({@link PromptVocabularyRenderer}) and the output JSON schema. It is identical for every
 * ingestion of a brain at the same {@code configVersion}, so providers can cache it. Everything
 * derived from a request or from the graph (content, source refs, speaker, extraction output,
 * candidate names and claims) goes into the user message inside nonce-tagged {@link DataBlocks}.
 *
 * <p>No graph id ever appears in a prompt: candidates are shown under their opaque handles only.
 */
public final class IngestionPrompts {

    public static final String EXTRACT = "extract-v1";
    public static final String RECONCILE = "reconcile-v1";
    public static final String REPAIR = "repair-v1";
    /** Repair calls see at most this much of the broken output (ARCHITECTURE §4.3). */
    public static final int REPAIR_MAX_PREVIOUS_CHARS = 8 * 1024;
    public static final int CANDIDATE_DESCRIPTION_CHARS = 300;
    /** Per-field caps in the candidates block (ADR-031). */
    public static final int CANDIDATE_TEXT_CHARS = 200;
    public static final int CANDIDATE_ALIAS_CHARS = 100;
    public static final int CANDIDATE_MAX_ALIASES = 3;
    /** Default estimated-token budgets of the candidates block and of the extraction hint (ADR-031). */
    public static final int DEFAULT_CANDIDATE_TOKENS = 8_000;
    public static final int DEFAULT_EXTRACTION_TOKENS = 3_000;

    private final PromptVocabularyRenderer vocabularyRenderer = new PromptVocabularyRenderer();
    private final OutputSchemaRenderer schemaRenderer = new OutputSchemaRenderer();
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, PromptTemplate> templates = new LinkedHashMap<>();
    private final int candidateTokens;
    private final int extractionTokens;

    public IngestionPrompts() {
        this(DEFAULT_CANDIDATE_TOKENS, DEFAULT_EXTRACTION_TOKENS);
    }

    /**
     * @param candidateTokens estimated-token budget of the candidates block: lower-ranked candidates
     *     are left out beyond it
     * @param extractionTokens estimated-token budget of the extraction hint: trailing statements, then
     *     mentions are left out beyond it
     */
    public IngestionPrompts(int candidateTokens, int extractionTokens) {
        if (candidateTokens < 100 || extractionTokens < 100) {
            throw new IllegalArgumentException("prompt budgets must be at least 100 tokens");
        }
        this.candidateTokens = candidateTokens;
        this.extractionTokens = extractionTokens;
        for (String id : List.of(EXTRACT + ".system", EXTRACT + ".user", RECONCILE + ".system", RECONCILE + ".user",
                REPAIR + ".system", REPAIR + ".user")) {
            templates.put(id, PromptTemplate.load(id));
        }
    }

    /** A source reference of the request: never fetched, only its text fields are shown. */
    public record PromptSource(String kind, String locator, String title, String summary) {
    }

    /** Who "I" is, when the request names a speaker; null means the brain owner (self). */
    public record Speaker(String type, String name) {
    }

    /**
     * An existing entity offered to the reconcile stage under its handle.
     *
     * @param rank how well it matches a mention (its best candidate score, ADR-030); higher ranks are
     *     kept when the candidates block is over budget. Null for a context-only entity (the object of
     *     a candidate's claim), which is shown only while a claim shown needs it.
     */
    public record PromptEntity(String handle, String type, String name, List<String> aliases, String description,
            Double rank) {
        public PromptEntity {
            Objects.requireNonNull(handle, "handle");
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }

        public PromptEntity(String handle, String type, String name, List<String> aliases, String description) {
            this(handle, type, name, aliases, description, 0.0);
        }
    }

    /** An ACTIVE claim offered to the reconcile stage; subject and object are handles. */
    public record PromptClaim(String handle, String subject, String predicate, String object, String value,
            String polarity, String statement, String origin) {
        public PromptClaim {
            Objects.requireNonNull(handle, "handle");
        }
    }

    /** Everything a stage prompt needs; {@code extraction} is used by reconcile only. */
    public record Input(BrainVocabulary vocabulary, Limits limits, String content, String contentType,
            Speaker speaker, List<PromptSource> sources, List<PromptEntity> entities, List<PromptClaim> claims,
            String extraction) {
        public Input {
            Objects.requireNonNull(vocabulary, "vocabulary");
            Objects.requireNonNull(limits, "limits");
            Objects.requireNonNull(content, "content");
            sources = sources == null ? List.of() : List.copyOf(sources);
            entities = entities == null ? List.of() : List.copyOf(entities);
            claims = claims == null ? List.of() : List.copyOf(claims);
        }
    }

    /** A rendered prompt: id, the two messages, and the nonce its data blocks use. */
    /** @param shownHandles candidate handles shown in the prompt (reconcile), or null when none apply */
    public record RenderedPrompt(String promptId, String system, String user, String nonce, Set<String> shownHandles) {

        public RenderedPrompt(String promptId, String system, String user, String nonce) {
            this(promptId, system, user, nonce, null);
        }
    }

    // ------------------------------------------------------------------ stages

    public RenderedPrompt extract(Input in) {
        DataBlocks blocks = blocksFor(in, List.of());
        String system = templates.get(EXTRACT + ".system").render(Map.of(
                "nonce", blocks.nonce(),
                "vocabulary", vocabularyRenderer.render(in.vocabulary()).strip(),
                "schema", extractionSchema(in.vocabulary(), in.limits())));
        String user = templates.get(EXTRACT + ".user").render(Map.of(
                "contentType", contentType(in),
                "speaker", speakerBlock(blocks, in.speaker()),
                "sources", sourcesBlock(blocks, in.sources()),
                "content", blocks.wrap("content", in.content())));
        return new RenderedPrompt(EXTRACT, system, user, blocks.nonce());
    }

    public RenderedPrompt reconcile(Input in) {
        CandidatesBlock block = candidatesBlock(in.entities(), in.claims());
        String candidates = block.text();
        String extraction = boundedExtraction(in.extraction());
        DataBlocks blocks = blocksFor(in, List.of(candidates, extraction));
        String system = templates.get(RECONCILE + ".system").render(Map.of(
                "nonce", blocks.nonce(),
                "vocabulary", vocabularyRenderer.render(in.vocabulary()).strip(),
                "schema", proposalSchema(in.vocabulary(), in.limits())));
        String user = templates.get(RECONCILE + ".user").render(Map.of(
                "contentType", contentType(in),
                "speaker", speakerBlock(blocks, in.speaker()),
                "sources", sourcesBlock(blocks, in.sources()),
                "candidates", blocks.wrap("candidates", candidates),
                "extraction", blocks.wrap("extraction", extraction),
                "content", blocks.wrap("content", in.content())));
        return new RenderedPrompt(RECONCILE, system, user, blocks.nonce(), block.shown());
    }

    /**
     * One repair attempt for output of {@code stage} ({@link #EXTRACT} or {@link #RECONCILE}) that
     * could not be parsed. Only the broken output (truncated) and the error go in; no new content.
     */
    public RenderedPrompt repair(String stage, BrainVocabulary vocabulary, Limits limits, String previousOutput,
            String error) {
        String schema;
        String empty;
        if (EXTRACT.equals(stage)) {
            schema = extractionSchema(vocabulary, limits);
            empty = "{\"summary\":\"\",\"mentions\":[],\"statements\":[]}";
        } else if (RECONCILE.equals(stage)) {
            schema = proposalSchema(vocabulary, limits);
            empty = "{\"summary\":\"\",\"operations\":[]}";
        } else {
            throw new IllegalArgumentException("unknown stage " + stage);
        }
        String previous = previousOutput == null ? "" : previousOutput;
        if (previous.length() > REPAIR_MAX_PREVIOUS_CHARS) {
            previous = previous.substring(0, REPAIR_MAX_PREVIOUS_CHARS);
        }
        String err = Normalizer.truncate(Normalizer.normalizeWhitespace(error == null ? "unparseable" : error), 300);
        DataBlocks blocks = DataBlocks.forTexts(List.of(previous, err));
        String system = templates.get(REPAIR + ".system").render(Map.of(
                "nonce", blocks.nonce(),
                "schema", schema,
                "emptyAnswer", empty));
        String user = templates.get(REPAIR + ".user").render(Map.of(
                "error", blocks.wrap("error", err),
                "previous", blocks.wrap("previous_answer", previous)));
        return new RenderedPrompt(REPAIR, system, user, blocks.nonce());
    }

    /**
     * The {@link OutputSchemaRenderer} proposal schema, compacted: pretty-printing costs roughly a
     * third more prompt tokens for no benefit to the model.
     */
    public String proposalSchema(BrainVocabulary vocabulary, Limits limits) {
        return json.writeValueAsString(schemaRenderer.renderNode(vocabulary, limits));
    }

    // ------------------------------------------------------------------ sections

    private static DataBlocks blocksFor(Input in, List<String> more) {
        List<String> texts = new ArrayList<>(more);
        texts.add(in.content());
        if (in.speaker() != null) {
            texts.add(in.speaker().type());
            texts.add(in.speaker().name());
        }
        for (PromptSource s : in.sources()) {
            texts.add(s.kind());
            texts.add(s.locator());
            texts.add(s.title());
            texts.add(s.summary());
        }
        return DataBlocks.forTexts(texts);
    }

    private static String contentType(Input in) {
        String ct = in.contentType() == null ? "TEXT" : in.contentType();
        return ct.matches("[A-Z_]{1,32}") ? ct : "TEXT";
    }

    private String speakerBlock(DataBlocks blocks, Speaker speaker) {
        if (speaker == null) {
            return "No speaker was named: the speaker (handle P0) is the owner of this brain.";
        }
        ObjectNode n = json.createObjectNode();
        n.put("id", "P0");
        n.put("type", speaker.type());
        n.put("name", speaker.name());
        return "The speaker (handle P0) is described here:\n" + blocks.wrap("speaker", json.writeValueAsString(n));
    }

    private String sourcesBlock(DataBlocks blocks, List<PromptSource> sources) {
        if (sources.isEmpty()) {
            return "No sources were attached.";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            PromptSource s = sources.get(i);
            ObjectNode n = json.createObjectNode();
            n.put("source", i);
            n.put("kind", s.kind());
            n.put("locator", s.locator());
            if (s.title() != null) {
                n.put("title", s.title());
            }
            if (s.summary() != null) {
                n.put("summary", s.summary());
            }
            sb.append(json.writeValueAsString(n)).append('\n');
        }
        return blocks.wrap("sources", sb.toString());
    }

    /**
     * A short description of where {@code raw} stops being valid JSON (line, column and the text
     * around it), for the repair prompt. Small models repeat their output verbatim when they are
     * only told that it is invalid; pointing at the fault makes the repair work.
     */
    public String syntaxHint(String raw, String parserMessage) {
        String base = parserMessage == null ? "invalid output" : parserMessage;
        if (raw == null) {
            return base;
        }
        int start = raw.indexOf('{');
        if (start < 0) {
            return base + "; the answer contains no JSON object";
        }
        String body = raw.substring(start);
        try {
            json.readTree(body);
            return base;
        } catch (tools.jackson.core.JacksonException e) {
            var loc = e.getLocation();
            if (loc == null || loc.getCharOffset() < 0) {
                return base + "; " + Normalizer.truncate(e.getOriginalMessage(), 120);
            }
            int at = (int) Math.min(loc.getCharOffset(), body.length());
            String around = body.substring(Math.max(0, at - 60), Math.min(body.length(), at + 20));
            return base + "; JSON syntax error at line " + loc.getLineNr() + " column " + loc.getColumnNr() + ": "
                    + Normalizer.truncate(e.getOriginalMessage(), 120) + ". Text before and at the error: " + around;
        }
    }

    /**
     * One JSON object per line: entities first, then claims, within the candidates budget (ADR-031).
     * Texts are capped per field. Over budget, the lowest-ranked items are left out first: a claim
     * ranks as its subject, and goes before an entity of the same rank; leaving out an entity leaves
     * out its claims. A context-only entity is shown only while a shown claim needs it; the speaker
     * {@code P0} always stays.
     */
    String candidatesText(List<PromptEntity> entities, List<PromptClaim> claims) {
        return candidatesBlock(entities, claims).text();
    }

    /** The candidates text and the handles it shows (only those can be referenced, ADR-034). */
    record CandidatesBlock(String text, Set<String> shown) {
    }

    CandidatesBlock candidatesBlock(List<PromptEntity> entities, List<PromptClaim> claims) {
        if (entities.isEmpty() && claims.isEmpty()) {
            return new CandidatesBlock("(no existing candidates)\n", Set.of());
        }
        Set<String> shownHandles = new HashSet<>();
        Map<String, PromptEntity> byHandle = new LinkedHashMap<>();
        entities.forEach(e -> byHandle.put(e.handle(), e));
        long[] entityTokens = new long[entities.size()];
        long[] claimTokens = new long[claims.size()];
        List<String> entityLines = new ArrayList<>();
        List<String> claimLines = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            entityLines.add(entityLine(entities.get(i)));
            entityTokens[i] = TokenEstimator.estimate(entityLines.get(i)) + 1;
        }
        for (int i = 0; i < claims.size(); i++) {
            claimLines.add(claimLine(claims.get(i)));
            claimTokens[i] = TokenEstimator.estimate(claimLines.get(i)) + 1;
        }
        boolean[] entityShown = new boolean[entities.size()];
        boolean[] claimShown = new boolean[claims.size()];
        Arrays.fill(entityShown, true);
        Arrays.fill(claimShown, true);
        // drop order: rank ascending, claims before entities, later before earlier (index encodes both)
        List<int[]> order = new ArrayList<>(); // {kind 0=claim 1=entity, index}
        for (int i = 0; i < claims.size(); i++) {
            order.add(new int[] {0, i});
        }
        for (int i = 0; i < entities.size(); i++) {
            PromptEntity e = entities.get(i);
            if (e.rank() != null && !"P0".equals(e.handle())) {
                order.add(new int[] {1, i});
            }
        }
        order.sort(Comparator.<int[]>comparingDouble(o -> o[0] == 0 ? rankOf(byHandle.get(claims.get(o[1])
                .subject())) : rankOf(entities.get(o[1]))).thenComparingInt(o -> o[0])
                .thenComparing(o -> o[1], Comparator.reverseOrder()));
        long total = shownTokens(entities, claims, entityShown, claimShown, entityTokens, claimTokens);
        for (int[] o : order) {
            if (total <= candidateTokens) {
                break;
            }
            if (o[0] == 0) {
                claimShown[o[1]] = false;
            } else {
                entityShown[o[1]] = false;
                String h = entities.get(o[1]).handle();
                for (int j = 0; j < claims.size(); j++) {
                    if (h.equals(claims.get(j).subject()) || h.equals(claims.get(j).object())) {
                        claimShown[j] = false;
                    }
                }
            }
            total = shownTokens(entities, claims, entityShown, claimShown, entityTokens, claimTokens);
        }
        StringBuilder sb = new StringBuilder();
        int omitted = 0;
        Set<String> needed = neededContext(claims, claimShown);
        for (int i = 0; i < entities.size(); i++) {
            if (visible(entities.get(i), entityShown[i], needed)) {
                sb.append(entityLines.get(i)).append('\n');
                shownHandles.add(entities.get(i).handle());
            } else {
                omitted++;
            }
        }
        for (int i = 0; i < claims.size(); i++) {
            if (claimShown[i]) {
                sb.append(claimLines.get(i)).append('\n');
                shownHandles.add(claims.get(i).handle());
            } else {
                omitted++;
            }
        }
        if (omitted > 0) {
            sb.append("(").append(omitted).append(" lower-ranked candidates omitted for length)\n");
        }
        return new CandidatesBlock(sb.toString(), Set.copyOf(shownHandles));
    }

    private static Set<String> neededContext(List<PromptClaim> claims, boolean[] claimShown) {
        Set<String> needed = new HashSet<>();
        for (int i = 0; i < claims.size(); i++) {
            if (claimShown[i]) {
                needed.add(claims.get(i).subject());
                needed.add(claims.get(i).object());
            }
        }
        return needed;
    }

    private static boolean visible(PromptEntity e, boolean shown, Set<String> needed) {
        return shown && (e.rank() != null || needed.contains(e.handle()));
    }

    private static long shownTokens(List<PromptEntity> entities, List<PromptClaim> claims, boolean[] entityShown,
            boolean[] claimShown, long[] entityTokens, long[] claimTokens) {
        Set<String> needed = neededContext(claims, claimShown);
        long total = 0;
        for (int i = 0; i < entities.size(); i++) {
            if (visible(entities.get(i), entityShown[i], needed)) {
                total += entityTokens[i];
            }
        }
        for (int i = 0; i < claims.size(); i++) {
            if (claimShown[i]) {
                total += claimTokens[i];
            }
        }
        return total;
    }

    private static double rankOf(PromptEntity e) {
        return e == null || e.rank() == null ? -1.0 : e.rank();
    }

    private String entityLine(PromptEntity e) {
        ObjectNode n = json.createObjectNode();
        n.put("id", e.handle());
        n.put("type", e.type());
        n.put("name", Normalizer.truncate(e.name(), CANDIDATE_TEXT_CHARS));
        if (!e.aliases().isEmpty()) {
            ArrayNode a = n.putArray("aliases");
            e.aliases().stream().limit(CANDIDATE_MAX_ALIASES)
                    .forEach(x -> a.add(Normalizer.truncate(x, CANDIDATE_ALIAS_CHARS)));
        }
        if (e.description() != null && !e.description().isBlank()) {
            n.put("description", Normalizer.truncate(e.description().strip(), CANDIDATE_DESCRIPTION_CHARS));
        }
        return json.writeValueAsString(n);
    }

    private String claimLine(PromptClaim c) {
        ObjectNode n = json.createObjectNode();
        n.put("claimId", c.handle());
        n.putObject("subject").put("id", c.subject());
        n.put("predicate", c.predicate());
        if (c.object() != null) {
            n.putObject("object").put("id", c.object());
        }
        if (c.value() != null) {
            n.put("value", Normalizer.truncate(c.value(), CANDIDATE_TEXT_CHARS));
        }
        if (c.polarity() != null && !"POSITIVE".equals(c.polarity())) {
            n.put("polarity", c.polarity());
        }
        if (c.statement() != null) {
            n.put("statement", Normalizer.truncate(c.statement(), CANDIDATE_TEXT_CHARS));
        }
        if ("MANUAL".equals(c.origin())) {
            n.put("protected", true);
        }
        return json.writeValueAsString(n);
    }

    /**
     * The extraction hint within its budget (ADR-031): trailing statements, then trailing mentions
     * are left out; an unreadable or still oversize hint becomes {@code {}}.
     */
    String boundedExtraction(String extraction) {
        String e = extraction == null || extraction.isBlank() ? "{}" : extraction.strip();
        if (TokenEstimator.estimate(e) <= extractionTokens) {
            return e;
        }
        JsonNode root;
        try {
            root = json.readTree(e);
        } catch (RuntimeException ex) {
            return "{}";
        }
        if (!(root instanceof ObjectNode o)) {
            return "{}";
        }
        for (String field : List.of("statements", "mentions")) {
            if (o.get(field) instanceof ArrayNode a) {
                while (!a.isEmpty() && TokenEstimator.estimate(json.writeValueAsString(o))
                        > extractionTokens) {
                    a.remove(a.size() - 1);
                }
            }
        }
        if (o.get("summary") != null && o.get("summary").isString()) {
            o.put("summary", Normalizer.truncate(o.get("summary").stringValue(), 500));
        }
        String out = json.writeValueAsString(o);
        return TokenEstimator.estimate(out) <= extractionTokens ? out : "{}";
    }

    /** JSON Schema of the stage-1 ExtractionResult (ARCHITECTURE §3), with vocabulary enums. */
    public String extractionSchema(BrainVocabulary vocabulary, Limits limits) {
        ObjectNode root = json.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("title", "GraphSpindle extraction (" + vocabulary.profile() + ")");
        root.put("type", "object");
        root.put("additionalProperties", false);
        root.putArray("required").add("summary").add("mentions").add("statements");
        ObjectNode props = root.putObject("properties");
        props.set("summary", string(1000));

        ObjectNode mentions = props.putObject("mentions");
        mentions.put("type", "array");
        mentions.put("maxItems", limits.maxNewEntities() * 2);
        ObjectNode m = mentions.putObject("items");
        m.put("type", "object");
        m.put("additionalProperties", false);
        m.putArray("required").add("localRef").add("name").add("type").add("evidence");
        ObjectNode mp = m.putObject("properties");
        mp.set("localRef", pattern(OutputSchemaRenderer.REF_PATTERN));
        mp.set("name", string(200));
        mp.set("type", enumOf(new ArrayList<>(vocabulary.entityTypeNames())));
        ObjectNode aliases = mp.putObject("aliases");
        aliases.put("type", "array");
        aliases.put("maxItems", limits.maxAliasesPerEntity());
        aliases.set("items", string(200));
        mp.set("description", string(1000));
        mp.set("evidence", described(string(300), "a verbatim quote from the input naming the mention"));

        Set<String> predicates = new LinkedHashSet<>();
        vocabulary.relationPredicates().forEach(p -> predicates.add(p.name()));
        predicates.addAll(vocabulary.attributeNames());
        ObjectNode statements = props.putObject("statements");
        statements.put("type", "array");
        statements.put("maxItems", limits.maxOperations());
        ObjectNode s = statements.putObject("items");
        s.put("type", "object");
        s.put("additionalProperties", false);
        s.putArray("required").add("subjectRef").add("predicate").add("statement").add("evidence")
                .add("confidence");
        ObjectNode sp = s.putObject("properties");
        sp.set("subjectRef", described(string(20), "a mention localRef, or P0 for the speaker"));
        sp.set("predicate", enumOf(new ArrayList<>(predicates)));
        sp.set("objectRef", described(string(20), "relation predicates: a mention localRef, or P0"));
        sp.set("value", described(string(2000), "attribute predicates: the literal value"));
        sp.set("polarity", enumOf(List.of("POSITIVE", "NEGATIVE")));
        sp.set("statement", string(500));
        sp.set("evidence", described(string(300), "a verbatim quote from the input"));
        ObjectNode confidence = sp.putObject("confidence");
        confidence.put("type", "number");
        confidence.put("minimum", 0);
        confidence.put("maximum", 1);
        sp.set("validFrom", pattern("^\\d{4}(-\\d{2}(-\\d{2})?)?$"));
        sp.set("validTo", pattern("^\\d{4}(-\\d{2}(-\\d{2})?)?$"));
        sp.putObject("correctionCue").put("type", "boolean");
        return json.writeValueAsString(root);
    }

    private ObjectNode string(int max) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "string");
        n.put("maxLength", max);
        return n;
    }

    private ObjectNode pattern(String regex) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "string");
        n.put("pattern", regex);
        return n;
    }

    private static ObjectNode described(ObjectNode n, String description) {
        n.put("description", description);
        return n;
    }

    private ObjectNode enumOf(List<String> values) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "string");
        ArrayNode e = n.putArray("enum");
        values.forEach(e::add);
        return n;
    }
}
