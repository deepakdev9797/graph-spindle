package io.graphspindle.llm;

import io.graphspindle.common.Hashing;
import io.graphspindle.common.Normalizer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deterministic, offline {@link ChatModel} for tests (ARCHITECTURE §7). No network access.
 *
 * <p><b>Routing.</b> Each call is routed by
 * <ol>
 *   <li>the <i>stage</i>: the prompt id on the first line of the system message
 *       ({@code GraphSpindle prompt reconcile-v1.}), and</li>
 *   <li>the <i>scenario</i>: a {@code [[fake-llm:name]]} marker in the content block if present,
 *       else {@code sha256(normalizeWhitespace(content))} of the nonce-delimited {@code content}
 *       data block. Repair prompts carry no content block; they go to the scenario of the previous
 *       non-repair call.</li>
 * </ol>
 * Each (scenario, stage) has a queue of scripted responses consumed in order. An unknown scenario or
 * an exhausted queue throws, so a test can never pass on an unscripted call.
 *
 * <p><b>Scripts</b> come from {@code src/test/resources/fake-llm/*.json} (field {@code content} and
 * {@code responses: {stageId: [entry...]}}) or are registered in code. An entry is one of
 * <pre>
 * {"json": {...}}              the JSON value, serialized compactly
 * {"raw": "text"}              exactly this text (malformed JSON, prose, code fences ...)
 * {"oversizeChars": 2100000}   a syntactically valid but huge proposal of at least that many characters
 * {"error": "timeout"}         throws ResourceAccessException(SocketTimeoutException), like RestClient
 * {"error": "io" | "runtime"}  throws ResourceAccessException(IOException) / IllegalStateException
 * </pre>
 * Any entry may add {@code "delayMs": n} to sleep before answering (for gateway timeout tests).
 * In {@code json} entries, string values {@code "@entity:<name>"} and {@code "@claim:<subject name>|<PREDICATE>"}
 * are replaced by the handles the engine showed in the prompt's {@code candidates} data block (for
 * end-to-end tests, where handles are assigned at run time); an unresolvable reference throws.
 *
 * <p><b>Recording.</b> Every call is recorded with its stage, scenario, system and user text and
 * options, so tests can assert on what the model saw (delimiters present, secrets redacted, no data
 * of another brain).
 */
public final class FakeChatModel implements ChatModel {

    public static final String RESOURCE_DIR = "fake-llm";
    private static final Pattern PROMPT_ID = Pattern.compile("^GraphSpindle prompt ([a-z0-9.-]+)\\.");
    private static final Pattern CONTENT_BLOCK = Pattern.compile(
            "<<<DATA nonce=([0-9a-f]{16}) name=content>>>\\n(.*?)\\n?<<<END DATA nonce=\\1>>>", Pattern.DOTALL);
    private static final Pattern MARKER = Pattern.compile("\\[\\[fake-llm:([a-z0-9-]+)]]");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One recorded call. */
    public record Call(int seq, String stage, String scenario, String system, String user, ChatOptions options) {
        /** System and user text together. */
        public String text() {
            return system + "\n" + user;
        }
    }

    private final Map<String, Map<String, Deque<JsonNode>>> scripts = new LinkedHashMap<>();
    private final Map<String, String> scenarioByHash = new LinkedHashMap<>();
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger seq = new AtomicInteger();
    private volatile String lastScenario;

    private static final Pattern CANDIDATES_BLOCK = Pattern.compile(
            "<<<DATA nonce=([0-9a-f]{16}) name=candidates>>>\\n(.*?)\\n?<<<END DATA nonce=\\1>>>", Pattern.DOTALL);

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder().model("fake-model").build();
    }

    /** Forgets every script and recorded call, then reloads the resource scripts. */
    public synchronized FakeChatModel reset() {
        scripts.clear();
        scenarioByHash.clear();
        calls.clear();
        lastScenario = null;
        loadResources(this);
        return this;
    }

    /** Loads every {@code fake-llm/*.json} script from the test classpath. */
    public static FakeChatModel fromResources() {
        return loadResources(new FakeChatModel());
    }

    private static FakeChatModel loadResources(FakeChatModel fake) {
        URL dir = FakeChatModel.class.getClassLoader().getResource(RESOURCE_DIR);
        Objects.requireNonNull(dir, "no " + RESOURCE_DIR + " directory on the classpath");
        try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
            files.filter(p -> p.toString().endsWith(".json")).sorted().forEach(p -> {
                try (InputStream in = Files.newInputStream(p)) {
                    fake.load(JSON.readTree(in));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        return fake;
    }

    /** Registers one scenario document ({@code scenario}, {@code content}, {@code responses}). */
    public FakeChatModel load(JsonNode doc) {
        String name = doc.required("scenario").asString();
        String content = doc.required("content").asString();
        scenarioByHash.put(contentKey(content), name);
        Map<String, Deque<JsonNode>> byStage = new LinkedHashMap<>();
        JsonNode responses = doc.path("responses");
        for (String stage : responses.propertyNames()) {
            Deque<JsonNode> queue = new ArrayDeque<>();
            responses.get(stage).forEach(queue::add);
            byStage.put(stage, queue);
        }
        scripts.put(name, byStage);
        return this;
    }

    /** Registers responses in code: {@code script("x", "hello", "reconcile-v1", "{\"raw\":\"...\"}")}. */
    public FakeChatModel script(String scenario, String content, String stage, String... entries) {
        scenarioByHash.put(contentKey(content), scenario);
        Deque<JsonNode> queue = scripts.computeIfAbsent(scenario, k -> new LinkedHashMap<>())
                .computeIfAbsent(stage, k -> new ArrayDeque<>());
        for (String e : entries) {
            queue.add(JSON.readTree(e));
        }
        return this;
    }

    public static String contentKey(String content) {
        Matcher m = MARKER.matcher(content);
        if (m.find()) {
            return "marker:" + m.group(1);
        }
        return "sha256:" + Hashing.sha256Hex(Normalizer.normalizeWhitespace(content));
    }

    public List<Call> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    public List<Call> calls(String stage) {
        return calls().stream().filter(c -> c.stage().equals(stage)).toList();
    }

    public int callCount() {
        return calls.size();
    }

    /** Scripted responses not yet consumed, per scenario and stage (to assert a script was used up). */
    public Map<String, Integer> remaining(String scenario) {
        Map<String, Integer> out = new LinkedHashMap<>();
        scripts.getOrDefault(scenario, Map.of()).forEach((k, v) -> out.put(k, v.size()));
        return out;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String system = text(prompt, MessageType.SYSTEM);
        String user = text(prompt, MessageType.USER);
        Matcher id = PROMPT_ID.matcher(system);
        if (!id.find()) {
            throw new IllegalStateException("fake LLM: system message carries no GraphSpindle prompt id");
        }
        String stage = id.group(1);
        String scenario;
        if (stage.startsWith("repair-")) {
            scenario = Objects.requireNonNull(lastScenario, "fake LLM: repair call before any stage call");
        } else {
            Matcher content = CONTENT_BLOCK.matcher(user);
            if (!content.find()) {
                throw new IllegalStateException("fake LLM: user message has no nonce-delimited content block");
            }
            String key = contentKey(content.group(2));
            scenario = scenarioByHash.get(key);
            if (scenario == null) {
                throw new IllegalStateException("fake LLM: no script for content " + key);
            }
            lastScenario = scenario;
        }
        calls.add(new Call(seq.incrementAndGet(), stage, scenario, system, user, prompt.getOptions()));
        Deque<JsonNode> queue = scripts.getOrDefault(scenario, Map.of()).get(stage);
        JsonNode entry;
        synchronized (this) {
            entry = queue == null ? null : queue.poll();
        }
        if (entry == null) {
            throw new IllegalStateException("fake LLM: no (more) scripted " + stage + " responses for " + scenario);
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer(entry, user)))));
    }

    private static String answer(JsonNode entry, String user) {
        if (entry.has("delayMs")) {
            try {
                Thread.sleep(entry.get("delayMs").asLong());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }
        if (entry.has("error")) {
            String kind = entry.get("error").asString();
            switch (kind) {
                case "timeout" -> throw new ResourceAccessException("I/O error on POST request: Read timed out",
                        new SocketTimeoutException("Read timed out"));
                case "io" -> throw new ResourceAccessException("I/O error on POST request: Connection refused",
                        new IOException("Connection refused"));
                default -> throw new IllegalStateException("fake LLM: simulated provider failure");
            }
        }
        if (entry.has("json")) {
            return JSON.writeValueAsString(resolveHandles(entry.get("json"), user));
        }
        if (entry.has("raw")) {
            return entry.get("raw").asString();
        }
        if (entry.has("oversizeChars")) {
            return oversize(entry.get("oversizeChars").asInt());
        }
        throw new IllegalStateException("fake LLM: unknown script entry " + entry.propertyNames());
    }

    /** Replaces {@code @entity:} / {@code @claim:} references by the handles shown in the prompt. */
    static JsonNode resolveHandles(JsonNode node, String user) {
        if (node.isString()) {
            String v = node.stringValue();
            if (v.startsWith("@entity:") || v.startsWith("@claim:")) {
                return JSON.getNodeFactory().stringNode(handle(v, user));
            }
            return node;
        }
        if (node.isArray()) {
            var a = JSON.createArrayNode();
            node.forEach(e -> a.add(resolveHandles(e, user)));
            return a;
        }
        if (node.isObject()) {
            var o = JSON.createObjectNode();
            node.propertyNames().forEach(k -> o.set(k, resolveHandles(node.get(k), user)));
            return o;
        }
        return node;
    }

    private static String handle(String ref, String user) {
        Matcher m = CANDIDATES_BLOCK.matcher(user);
        if (!m.find()) {
            throw new IllegalStateException("fake LLM: no candidates block to resolve " + ref);
        }
        Map<String, String> entityHandles = new LinkedHashMap<>();
        List<JsonNode> claims = new ArrayList<>();
        for (String line : m.group(2).split("\n")) {
            if (!line.startsWith("{")) {
                continue;
            }
            JsonNode n = JSON.readTree(line);
            if (n.has("claimId")) {
                claims.add(n);
            } else {
                entityHandles.putIfAbsent(n.get("name").asString().toLowerCase(java.util.Locale.ROOT),
                        n.get("id").asString());
            }
        }
        if (ref.startsWith("@entity:")) {
            String h = entityHandles.get(ref.substring(8).toLowerCase(java.util.Locale.ROOT));
            if (h == null) {
                throw new IllegalStateException("fake LLM: no candidate named " + ref + " in " + entityHandles);
            }
            return h;
        }
        String[] parts = ref.substring(7).split("\\|");
        String subject = "P0".equals(parts[0]) ? "P0" : entityHandles.get(parts[0].toLowerCase(java.util.Locale.ROOT));
        return claims.stream().filter(c -> c.path("subject").path("id").asString().equals(subject)
                && c.path("predicate").asString().equals(parts[1])).map(c -> c.get("claimId").asString()).findFirst()
                .orElseThrow(() -> new IllegalStateException("fake LLM: no candidate claim " + ref));
    }

    /** A valid-looking proposal of at least {@code chars} characters (many identical ops). */
    static String oversize(int chars) {
        String op = "{\"op\":\"create_entity\",\"ref\":\"e1\",\"type\":\"Concept\",\"name\":\"filler\"},";
        StringBuilder sb = new StringBuilder(chars + 64).append("{\"summary\":\"big\",\"operations\":[");
        while (sb.length() < chars) {
            sb.append(op);
        }
        sb.setLength(sb.length() - 1);
        return sb.append("]}").toString();
    }

    private static String text(Prompt prompt, MessageType type) {
        StringBuilder sb = new StringBuilder();
        for (Message m : prompt.getInstructions()) {
            if (m.getMessageType() == type && m.getText() != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(m.getText());
            }
        }
        return sb.toString();
    }

    /** UTF-8 text of a classpath resource (for tests that compare against fixture files). */
    public static String resource(String path) {
        try (InputStream in = FakeChatModel.class.getClassLoader().getResourceAsStream(path)) {
            return new String(Objects.requireNonNull(in, path).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
