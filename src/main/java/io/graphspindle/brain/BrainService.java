package io.graphspindle.brain;

import io.graphspindle.common.BrainScope;
import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Normalizer;
import io.graphspindle.graph.BrainRecord;
import io.graphspindle.graph.BrainStore;
import io.graphspindle.vocabulary.BrainVocabulary;
import io.graphspindle.vocabulary.InvalidVocabularyConfigException;
import io.graphspindle.vocabulary.VocabularyConfig;
import io.graphspindle.vocabulary.VocabularyRegistry;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Brain lifecycle and configuration. Authorization ({@code BrainAccessGuard}) is added in M9.
 * Unknown and syntactically invalid brain ids both yield {@code BRAIN_NOT_FOUND}.
 */
@Service
public class BrainService {

    static final int MAX_NAME = 100;
    static final int MAX_DESCRIPTION = 1000;
    static final int MAX_OWNER_NAME = 200;

    private final BrainStore store;
    private final VocabularyRegistry vocabularies;
    private final JsonMapper writer;
    private final JsonMapper reader;

    public BrainService(BrainStore store, JsonMapper jsonMapper) {
        this.store = store;
        this.vocabularies = new VocabularyRegistry();
        this.writer = jsonMapper;
        // stored configs are read leniently so that removed fields never make a brain unreadable
        this.reader = jsonMapper.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    public Brain create(NewBrain request) {
        BrainScope scope = BrainScope.of(request.id());
        String name = requireName(request.name());
        String description = optional("description", request.description(), MAX_DESCRIPTION);
        String owner = optional("ownerName", request.ownerName(), MAX_OWNER_NAME);
        BrainConfig config = validated(request.config() == null ? BrainConfig.defaults() : request.config());
        BrainRecord r = store.create(scope, name, description, serialize(config), owner == null ? "Me" : owner);
        return toBrain(r);
    }

    public List<Brain> list() {
        return store.listAll().stream().map(this::toBrain).toList();
    }

    public Brain get(String brainId) {
        return toBrain(find(brainId));
    }

    /** Resolves an existing, ACTIVE brain to its scope; {@code CONFLICT_STATE} while it is being deleted. */
    public BrainScope requireActive(String brainId) {
        BrainRecord r = find(brainId);
        if (!r.active()) {
            throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "brain is being deleted");
        }
        return BrainScope.of(r.id());
    }

    public Brain update(String brainId, Long expectedVersion, BrainUpdate update) {
        BrainScope scope = scopeOrNotFound(brainId);
        String name = update.name() == null ? null : requireName(update.name());
        String description = optional("description", update.description(), MAX_DESCRIPTION);
        String configJson = update.config() == null ? null : serialize(validated(update.config()));
        return toBrain(store.update(scope, expectedVersion, name, description != null, description, configJson));
    }

    /** Deletes the brain and all its nodes. {@code confirm} must equal the brain id. */
    public void delete(String brainId, String confirm) {
        BrainScope scope = BrainScope.of(find(brainId).id());
        if (!brainId.equals(confirm)) {
            throw GraphSpindleException.validation("deleting a brain requires confirm=<brainId>");
        }
        store.delete(scope);
    }

    public BrainVocabularyView vocabularyView(String brainId) {
        Brain brain = get(brainId);
        return new BrainVocabularyView(brain, resolve(brain.config().vocabulary()));
    }

    /** Validates (SCHEMA §11) and stores a new vocabulary config; bumps {@code configVersion}. */
    public BrainVocabularyView updateVocabulary(String brainId, Long expectedVersion, VocabularyConfig vocabulary) {
        if (vocabulary == null) {
            throw GraphSpindleException.validation("vocabulary configuration is required");
        }
        BrainVocabulary resolved = resolve(vocabulary);
        Brain current = get(brainId);
        Brain updated = update(brainId, expectedVersion, new BrainUpdate(null, null,
                current.config().withVocabulary(vocabulary)));
        return new BrainVocabularyView(updated, resolved);
    }

    /** The resolved vocabulary of a brain (for ingestion and knowledge services). */
    public BrainVocabulary vocabulary(BrainScope scope) {
        return resolve(config(scope).vocabulary());
    }

    public BrainConfig config(BrainScope scope) {
        return get(scope.brainId()).config();
    }

    private BrainVocabulary resolve(VocabularyConfig config) {
        try {
            return vocabularies.forBrain(config);
        } catch (InvalidVocabularyConfigException e) {
            throw new GraphSpindleException(ErrorCode.VALIDATION_FAILED, "invalid vocabulary configuration",
                    Map.of("issues", e.issues()), e);
        }
    }

    private BrainConfig validated(BrainConfig config) {
        List<String> problems = config.problems();
        if (!problems.isEmpty()) {
            throw new GraphSpindleException(ErrorCode.VALIDATION_FAILED, "invalid brain configuration",
                    Map.of("issues", problems), null);
        }
        resolve(config.vocabulary());
        return config;
    }

    private BrainRecord find(String brainId) {
        return store.find(scopeOrNotFound(brainId)).orElseThrow(() -> GraphSpindleException.brainNotFound(brainId));
    }

    private static BrainScope scopeOrNotFound(String brainId) {
        return BrainScope.parse(brainId).orElseThrow(() -> GraphSpindleException.brainNotFound(brainId));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw GraphSpindleException.validation("name is required");
        }
        String n = name.strip();
        if (Normalizer.codePointLength(n) > MAX_NAME) {
            throw GraphSpindleException.validation("name must be at most " + MAX_NAME + " characters");
        }
        return n;
    }

    private static String optional(String field, String value, int max) {
        if (value == null) {
            return null;
        }
        if (Normalizer.codePointLength(value) > max) {
            throw GraphSpindleException.validation(field + " must be at most " + max + " characters");
        }
        return value;
    }

    private String serialize(BrainConfig config) {
        return writer.writeValueAsString(config);
    }

    private Brain toBrain(BrainRecord r) {
        BrainConfig config;
        try {
            config = reader.readValue(r.configJson(), BrainConfig.class);
        } catch (JacksonException e) {
            throw new GraphSpindleException(ErrorCode.INTERNAL, "stored brain configuration is unreadable",
                    Map.of(), e);
        }
        return new Brain(r.id(), r.name(), r.description(), r.status(), r.configVersion(), r.selfEntityId(), config,
                r.createdAt(), r.updatedAt());
    }
}
