package io.graphspindle.ingestion.pipeline;

import io.graphspindle.common.ClaimKind;
import io.graphspindle.common.Limits;
import io.graphspindle.common.Normalizer;
import io.graphspindle.common.Origin;
import io.graphspindle.common.Polarity;
import io.graphspindle.ingestion.pipeline.IngestionPrompts.PromptClaim;
import io.graphspindle.ingestion.pipeline.IngestionPrompts.PromptEntity;
import io.graphspindle.ingestion.pipeline.IngestionPrompts.PromptSource;
import io.graphspindle.ingestion.pipeline.IngestionPrompts.Speaker;
import io.graphspindle.ingestion.validation.CandidateSet;
import io.graphspindle.ingestion.validation.ClaimCandidate;
import io.graphspindle.ingestion.validation.EntityCandidate;
import io.graphspindle.ingestion.validation.ValidationContext;
import io.graphspindle.llm.FakeChatModel;
import io.graphspindle.vocabulary.BrainVocabulary;
import io.graphspindle.vocabulary.ValueType;
import io.graphspindle.vocabulary.VocabularyRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One scenario fixture from {@code src/test/resources/fake-llm/<name>.json}: the request
 * (content, sources, speaker), a hand-built stage-2 candidate set (what the engine would have
 * retrieved from the brain), the scripted fake-LLM responses and the expected outcome.
 *
 * <p>Candidate ids are {@code id-<handle>}; they never reach a prompt.
 */
public record Scenario(String name, String description, String brainId, String content, String contentType,
        Speaker speaker, List<PromptSource> sources, List<PromptEntity> promptEntities, List<PromptClaim> promptClaims,
        CandidateSet candidates, boolean live, JsonNode expect, JsonNode doc) {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final BrainVocabulary CORE = new VocabularyRegistry().core();

    public static List<Scenario> all() {
        try {
            Path dir = Path.of(Scenario.class.getClassLoader().getResource(FakeChatModel.RESOURCE_DIR).toURI());
            try (Stream<Path> files = Files.list(dir)) {
                return files.filter(p -> p.toString().endsWith(".json")).sorted().map(Scenario::read).toList();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Scenario named(String name) {
        return all().stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no scenario " + name));
    }

    static Scenario read(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            return of(JSON.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Scenario of(JsonNode d) {
        List<PromptSource> sources = new ArrayList<>();
        d.path("sources").forEach(s -> sources.add(new PromptSource(s.path("kind").asString("FILE"),
                s.path("locator").asString(), text(s, "title"), text(s, "summary"))));
        Speaker speaker = d.has("speaker") ? new Speaker(d.get("speaker").path("type").asString(),
                d.get("speaker").path("name").asString()) : null;

        List<PromptEntity> pe = new ArrayList<>();
        List<PromptClaim> pc = new ArrayList<>();
        CandidateSet.Builder cs = CandidateSet.builder();
        boolean hasSelf = false;
        for (JsonNode e : d.path("candidates").path("entities")) {
            String handle = e.required("handle").asString();
            List<String> aliases = new ArrayList<>();
            e.path("aliases").forEach(a -> aliases.add(a.asString()));
            boolean self = e.path("self").asBoolean(false);
            hasSelf |= self;
            String type = e.required("type").asString();
            String name = e.required("name").asString();
            Set<String> mentionTypes = new HashSet<>();
            e.path("mentionTypes").forEach(t -> mentionTypes.add(t.asString()));
            if (self) {
                cs.entity(new EntityCandidate(handle, "id-" + handle, type, name, "@self", "person:@self", Set.of(),
                        false, Set.of()));
            } else {
                String norm = Normalizer.norm(name);
                Set<String> norms = new HashSet<>(Set.of(norm));
                aliases.forEach(a -> norms.add(Normalizer.norm(a)));
                cs.entity(new EntityCandidate(handle, "id-" + handle, type, name, norm,
                        Normalizer.canonicalKey(type, norm), norms, e.path("locked").asBoolean(false), mentionTypes));
            }
            pe.add(new PromptEntity(handle, type, name, aliases, text(e, "description")));
        }
        if (!hasSelf) {
            cs.entity(new EntityCandidate("P0", "id-P0", "Person", "Me", "@self", "person:@self", Set.of(), false,
                    Set.of()));
            pe.add(0, new PromptEntity("P0", "Person", "the speaker (brain owner)", List.of(), null));
        }
        for (JsonNode c : d.path("candidates").path("claims")) {
            String handle = c.required("handle").asString();
            String subject = c.required("subject").asString();
            String predicate = c.required("predicate").asString();
            String object = text(c, "object");
            String value = text(c, "value");
            Polarity polarity = Polarity.valueOf(c.path("polarity").asString("POSITIVE"));
            Origin origin = Origin.valueOf(c.path("origin").asString("LLM"));
            if (object != null) {
                cs.claim(new ClaimCandidate(handle, "id-" + handle, "id-" + subject, predicate, ClaimKind.RELATION,
                        "id-" + object, null, null, polarity, origin));
            } else {
                ValueType vt = ValueType.valueOf(c.path("valueType").asString("ENUM"));
                String norm = vt == ValueType.ENUM ? value.toUpperCase(Locale.ROOT) : Normalizer.norm(value);
                cs.claim(new ClaimCandidate(handle, "id-" + handle, "id-" + subject, predicate, ClaimKind.ATTRIBUTE,
                        null, vt, norm, polarity, origin));
            }
            pc.add(new PromptClaim(handle, subject, predicate, object, value, polarity.name(), text(c, "statement"),
                    origin.name()));
        }
        return new Scenario(d.required("scenario").asString(), d.path("description").asString(""),
                d.path("brainId").asString("dev"), d.required("content").asString(),
                d.path("contentType").asString("TEXT"), speaker, sources, pe, pc, cs.build(),
                d.path("live").asBoolean(false), d.path("expect"), d);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    public IngestionPrompts.Input input(Limits limits, String extraction) {
        return new IngestionPrompts.Input(CORE, limits, content, contentType, speaker, sources, promptEntities,
                promptClaims, extraction);
    }

    public ValidationContext validationContext() {
        List<String> summaries = sources.stream().map(PromptSource::summary).toList();
        return ValidationContext.of(brainId, CORE, candidates, content, summaries);
    }
}
