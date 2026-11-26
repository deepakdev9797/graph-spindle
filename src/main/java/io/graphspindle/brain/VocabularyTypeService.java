package io.graphspindle.brain;

import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Normalizer;
import io.graphspindle.vocabulary.AttributePredicateDef;
import io.graphspindle.vocabulary.BrainVocabulary;
import io.graphspindle.vocabulary.CoreProfile;
import io.graphspindle.vocabulary.EntityTypeDef;
import io.graphspindle.vocabulary.NodeType;
import io.graphspindle.vocabulary.PropertyDefinition;
import io.graphspindle.vocabulary.RelationPredicateDef;
import io.graphspindle.vocabulary.RelationshipType;
import io.graphspindle.vocabulary.VocabularyConfig;
import io.graphspindle.vocabulary.VocabularyConfig.CustomAttribute;
import io.graphspindle.vocabulary.VocabularyConfig.CustomAttributePredicate;
import io.graphspindle.vocabulary.VocabularyConfig.CustomEntityType;
import io.graphspindle.vocabulary.VocabularyConfig.CustomRelationPredicate;
import io.graphspindle.vocabulary.VocabularyConfigValidator;
import io.graphspindle.vocabulary.VocabularyRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;

/**
 * Node-type and relationship-type CRUD on top of the brain's vocabulary configuration (ADR-025).
 * Every change is a {@link VocabularyConfig} change applied through {@link BrainService} (optimistic
 * {@code configVersion}, validation, in-use guard, lock flag); there is no second vocabulary store.
 *
 * <p>Core types (from YAML) are read-only except: node types can be enabled/disabled and given extra
 * properties, relationship types can be enabled/disabled. Custom types are fully editable. REST and
 * MCP (M8) share these methods.
 */
@Service
public class VocabularyTypeService {

    private final BrainService brains;
    private final VocabularyRegistry registry;

    public VocabularyTypeService(BrainService brains) {
        this.brains = brains;
        this.registry = brains.registry();
    }

    /** A value together with the brain's {@code configVersion} (the ETag). */
    public record Versioned<T>(T value, long configVersion) {
    }

    // ---- global (core profile) view ---------------------------------------------------------

    public List<NodeType> coreNodeTypes() {
        return nodeTypes(VocabularyConfig.core());
    }

    public NodeType coreNodeType(String type) {
        return find(coreNodeTypes(), NodeType::name, type, "node type");
    }

    public List<RelationshipType> coreRelationshipTypes() {
        return relationshipTypes(VocabularyConfig.core());
    }

    public RelationshipType coreRelationshipType(String name) {
        return find(coreRelationshipTypes(), RelationshipType::name, name, "relationship type");
    }

    // ---- per-brain reads --------------------------------------------------------------------

    public Versioned<List<NodeType>> nodeTypes(String brainId) {
        Brain b = brains.get(brainId);
        return new Versioned<>(nodeTypes(b.config().vocabulary()), b.configVersion());
    }

    public Versioned<NodeType> nodeType(String brainId, String type) {
        Brain b = brains.get(brainId);
        return new Versioned<>(find(nodeTypes(b.config().vocabulary()), NodeType::name, type, "node type"),
                b.configVersion());
    }

    public Versioned<List<RelationshipType>> relationshipTypes(String brainId) {
        Brain b = brains.get(brainId);
        return new Versioned<>(relationshipTypes(b.config().vocabulary()), b.configVersion());
    }

    public Versioned<RelationshipType> relationshipType(String brainId, String name) {
        Brain b = brains.get(brainId);
        return new Versioned<>(find(relationshipTypes(b.config().vocabulary()), RelationshipType::name, name,
                "relationship type"), b.configVersion());
    }

    // ---- node type mutations ----------------------------------------------------------------

    public Versioned<NodeType> createNodeType(String brainId, Long expectedVersion, NodeType request) {
        brains.requireVocabularyMutable();
        requireBody(request);
        String name = requireTypeName(request.name());
        requireDescription(request.description());
        if (Boolean.FALSE.equals(request.enabled())) {
            throw GraphSpindleException.validation("a custom node type is always enabled; omit 'enabled'");
        }
        List<CustomAttribute> attributes = customAttributes(request.properties(), true);
        Brain b = mutate(brainId, expectedVersion, cfg -> {
            VocabularyGuardrails.checkNodeTypeName(registry, cfg, name, null);
            List<CustomEntityType> types = new ArrayList<>(cfg.customEntityTypes());
            types.add(new CustomEntityType(name, request.description().strip(), request.compatibilityGroup(),
                    attributes, request.synonyms(), request.examples()));
            return cfg.withCustomEntityTypes(types);
        });
        return new Versioned<>(find(nodeTypes(b.config().vocabulary()), NodeType::name, name, "node type"),
                b.configVersion());
    }

    public Versioned<NodeType> updateNodeType(String brainId, String type, Long expectedVersion, NodeType request) {
        brains.requireVocabularyMutable();
        requireBody(request);
        String[] resultName = {type};
        Brain b = mutate(brainId, expectedVersion, cfg -> {
            Optional<CustomEntityType> custom = cfg.customEntityTypes().stream()
                    .filter(t -> type.equals(t.name())).findFirst();
            if (custom.isPresent()) {
                return updateCustomType(cfg, custom.get(), request, resultName);
            }
            if (registry.profile().isCoreEntityType(type)) {
                return updateCoreType(cfg, type, request);
            }
            throw GraphSpindleException.notFound("node type", type);
        });
        return new Versioned<>(find(nodeTypes(b.config().vocabulary()), NodeType::name, resultName[0], "node type"),
                b.configVersion());
    }

    public long deleteNodeType(String brainId, String type, Long expectedVersion) {
        brains.requireVocabularyMutable();
        Brain b = mutate(brainId, expectedVersion, cfg -> {
            if (registry.profile().isCoreEntityType(type)) {
                throw GraphSpindleException.validation("core node type '" + type + "' is defined in the YAML "
                        + "vocabulary and cannot be deleted; disable it with PUT {\"enabled\": false}");
            }
            if (cfg.customEntityTypes().stream().noneMatch(t -> type.equals(t.name()))) {
                throw GraphSpindleException.notFound("node type", type);
            }
            List<String> referencing = cfg.customRelationPredicates().stream()
                    .filter(r -> r.sourceTypes().contains(type) || r.targetTypes().contains(type))
                    .map(CustomRelationPredicate::name).toList();
            if (!referencing.isEmpty()) {
                throw new GraphSpindleException(ErrorCode.CONFLICT_STATE, "node type '" + type
                        + "' is used by relationship types " + referencing + "; change or delete them first",
                        Map.of("referencedBy", referencing), null);
            }
            List<CustomEntityType> types = cfg.customEntityTypes().stream().filter(t -> !type.equals(t.name()))
                    .toList();
            List<CustomAttributePredicate> attrs = new ArrayList<>();
            for (CustomAttributePredicate a : cfg.customAttributePredicates()) {
                List<String> kept = a.entityTypes().stream().filter(t -> !type.equals(t)).toList();
                if (!kept.isEmpty()) {
                    attrs.add(withTypes(a, kept));
                }
            }
            List<String> enabled = cfg.enabledEntityTypes() == null ? null
                    : cfg.enabledEntityTypes().stream().filter(t -> !type.equals(t)).toList();
            return cfg.withCustomEntityTypes(types).withCustomAttributePredicates(attrs)
                    .withEnabledEntityTypes(enabled);
        });
        return b.configVersion();
    }

    private VocabularyConfig updateCustomType(VocabularyConfig cfg, CustomEntityType current, NodeType request,
            String[] resultName) {
        String name = request.name() == null ? current.name() : requireTypeName(request.name());
        if (!name.equals(current.name())) {
            VocabularyGuardrails.checkNodeTypeName(registry, cfg, name, current.name());
        }
        if (Boolean.FALSE.equals(request.enabled())) {
            throw GraphSpindleException.validation("a custom node type cannot be disabled; delete it instead");
        }
        if (request.description() != null) {
            requireDescription(request.description());
        }
        CustomEntityType next = new CustomEntityType(name,
                request.description() == null ? current.description() : request.description().strip(),
                request.compatibilityGroup() == null ? current.compatibilityGroup() : request.compatibilityGroup(),
                request.properties() == null ? current.attributes() : customAttributes(request.properties(), false),
                request.synonyms() == null ? current.synonyms() : request.synonyms(),
                request.examples() == null ? current.examples() : request.examples());
        List<CustomEntityType> types = cfg.customEntityTypes().stream()
                .map(t -> t.name().equals(current.name()) ? next : t).toList();
        VocabularyConfig out = cfg.withCustomEntityTypes(types);
        if (!name.equals(current.name())) {
            out = renameTypeReferences(out, current.name(), name);
        }
        resultName[0] = name;
        return out;
    }

    private VocabularyConfig updateCoreType(VocabularyConfig cfg, String type, NodeType request) {
        CoreProfile core = registry.profile();
        EntityTypeDef def = core.entityType(type).orElseThrow();
        readOnly("name", request.name(), def.name());
        readOnly("description", request.description(), def.description());
        readOnly("synonyms", request.synonyms(), def.synonyms());
        readOnly("examples", request.examples(), def.examples());
        readOnly("compatibilityGroup", request.compatibilityGroup(), def.compatibilityGroup());
        VocabularyConfig out = cfg;
        if (request.enabled() != null) {
            List<String> enabled = new ArrayList<>(cfg.enabledEntityTypes() == null
                    ? core.entityTypes().stream().map(EntityTypeDef::name).toList() : cfg.enabledEntityTypes());
            if (request.enabled() && !enabled.contains(type)) {
                enabled.add(type);
            } else if (!request.enabled()) {
                enabled.remove(type);
            }
            boolean allCore = enabled.containsAll(core.entityTypes().stream().map(EntityTypeDef::name).toList());
            out = out.withEnabledEntityTypes(allCore && cfg.enabledEntityTypes() == null ? null : enabled);
        }
        if (request.properties() != null) {
            List<AttributePredicateDef> coreProps = core.attributesOf(type);
            List<PropertyDefinition> extra = new ArrayList<>();
            for (PropertyDefinition p : request.properties()) {
                Optional<AttributePredicateDef> c = p == null ? Optional.empty()
                        : coreProps.stream().filter(a -> a.name().equals(p.name())).findFirst();
                if (c.isPresent()) {
                    unchangedCoreProperty(type, p, c.get());
                } else {
                    extra.add(p);
                }
            }
            List<CustomAttributePredicate> attrs = new ArrayList<>();
            for (CustomAttributePredicate a : cfg.customAttributePredicates()) {
                if (!a.entityTypes().equals(List.of(type))) {
                    attrs.add(a);
                }
            }
            for (CustomAttribute a : customAttributes(extra, false)) {
                attrs.add(new CustomAttributePredicate(a.name(), List.of(type), a.valueType(), a.maxLength(),
                        a.values(), a.min(), a.max(), a.description(), a.cardinality(), a.required(), a.synonyms()));
            }
            out = out.withCustomAttributePredicates(attrs);
        }
        return out;
    }

    private static void unchangedCoreProperty(String type, PropertyDefinition p, AttributePredicateDef core) {
        boolean changed = (p.type() != null && p.type() != core.valueType())
                || (p.maxLength() != null && !p.maxLength().equals(core.maxLength()))
                || (!p.values().isEmpty() && !p.values().equals(core.enumValues()))
                || (p.description() != null && !p.description().strip().equals(core.description()))
                || (p.cardinality() != null && p.cardinality() != core.cardinality())
                || (p.min() != null && !p.min().equals(core.min()))
                || (p.max() != null && !p.max().equals(core.max()));
        if (changed) {
            throw GraphSpindleException.validation("property " + type + "." + p.name() + " is defined in the YAML "
                    + "vocabulary and is read-only through the API");
        }
    }

    // ---- relationship type mutations --------------------------------------------------------

    public Versioned<RelationshipType> createRelationshipType(String brainId, Long expectedVersion,
            RelationshipType request) {
        brains.requireVocabularyMutable();
        requireBody(request);
        String name = requireRelationName(request.name());
        requireDescription(request.description());
        if (Boolean.FALSE.equals(request.enabled())) {
            throw GraphSpindleException.validation("a custom relationship type is always enabled; omit 'enabled'");
        }
        coreOnlyFieldsAbsent(request);
        Brain b = mutate(brainId, expectedVersion, cfg -> {
            VocabularyGuardrails.checkRelationName(registry, cfg, name, null);
            List<CustomRelationPredicate> rels = new ArrayList<>(cfg.customRelationPredicates());
            rels.add(new CustomRelationPredicate(name, request.description().strip(), request.sourceTypes(),
                    request.targetTypes(), request.cardinality(), request.synonyms()));
            return cfg.withCustomRelationPredicates(rels);
        });
        return new Versioned<>(find(relationshipTypes(b.config().vocabulary()), RelationshipType::name, name,
                "relationship type"), b.configVersion());
    }

    public Versioned<RelationshipType> updateRelationshipType(String brainId, String relation, Long expectedVersion,
            RelationshipType request) {
        brains.requireVocabularyMutable();
        requireBody(request);
        String[] resultName = {relation};
        Brain b = mutate(brainId, expectedVersion, cfg -> {
            Optional<CustomRelationPredicate> custom = cfg.customRelationPredicates().stream()
                    .filter(r -> relation.equals(r.name())).findFirst();
            if (custom.isPresent()) {
                return updateCustomRelation(cfg, custom.get(), request, resultName);
            }
            Optional<RelationPredicateDef> core = registry.profile().relation(relation);
            if (core.isPresent()) {
                return updateCoreRelation(cfg, core.get(), request);
            }
            throw GraphSpindleException.notFound("relationship type", relation);
        });
        return new Versioned<>(find(relationshipTypes(b.config().vocabulary()), RelationshipType::name,
                resultName[0], "relationship type"), b.configVersion());
    }

    public long deleteRelationshipType(String brainId, String relation, Long expectedVersion) {
        brains.requireVocabularyMutable();
        Brain b = mutate(brainId, expectedVersion, cfg -> {
            if (registry.profile().isCoreRelation(relation)) {
                throw GraphSpindleException.validation("core relationship type '" + relation + "' is defined in "
                        + "the YAML vocabulary and cannot be deleted; disable it with PUT {\"enabled\": false}");
            }
            if (cfg.customRelationPredicates().stream().noneMatch(r -> relation.equals(r.name()))) {
                throw GraphSpindleException.notFound("relationship type", relation);
            }
            Map<String, VocabularyConfig.PredicateOverride> overrides = new LinkedHashMap<>(cfg.predicateOverrides());
            overrides.remove(relation);
            VocabularyConfig out = cfg.withCustomRelationPredicates(cfg.customRelationPredicates().stream()
                    .filter(r -> !relation.equals(r.name())).toList());
            return withOverrides(out, overrides);
        });
        return b.configVersion();
    }

    private VocabularyConfig updateCustomRelation(VocabularyConfig cfg, CustomRelationPredicate current,
            RelationshipType request, String[] resultName) {
        coreOnlyFieldsAbsent(request);
        if (Boolean.FALSE.equals(request.enabled())) {
            throw GraphSpindleException.validation("a custom relationship type cannot be disabled; delete it instead");
        }
        String name = request.name() == null ? current.name() : requireRelationName(request.name());
        if (!name.equals(current.name())) {
            VocabularyGuardrails.checkRelationName(registry, cfg, name, current.name());
        }
        if (request.description() != null) {
            requireDescription(request.description());
        }
        CustomRelationPredicate next = new CustomRelationPredicate(name,
                request.description() == null ? current.description() : request.description().strip(),
                request.sourceTypes() == null ? current.sourceTypes() : request.sourceTypes(),
                request.targetTypes() == null ? current.targetTypes() : request.targetTypes(),
                request.cardinality() == null ? current.cardinality() : request.cardinality(),
                request.synonyms() == null ? current.synonyms() : request.synonyms());
        VocabularyConfig out = cfg.withCustomRelationPredicates(cfg.customRelationPredicates().stream()
                .map(r -> r.name().equals(current.name()) ? next : r).toList());
        if (!name.equals(current.name()) && cfg.predicateOverrides().containsKey(current.name())) {
            Map<String, VocabularyConfig.PredicateOverride> overrides = new LinkedHashMap<>(cfg.predicateOverrides());
            overrides.put(name, overrides.remove(current.name()));
            out = withOverrides(out, overrides);
        }
        resultName[0] = name;
        return out;
    }

    private VocabularyConfig updateCoreRelation(VocabularyConfig cfg, RelationPredicateDef def,
            RelationshipType request) {
        RelationshipType view = RelationshipType.of(def, true);
        readOnly("name", request.name(), def.name());
        readOnly("description", request.description(), def.description());
        readOnly("synonyms", request.synonyms(), def.synonyms());
        readOnly("sourceTypes", request.sourceTypes(), view.sourceTypes());
        readOnly("targetTypes", request.targetTypes(), view.targetTypes());
        readOnly("targetSameAsSource", request.targetSameAsSource(), view.targetSameAsSource());
        readOnly("extraTargetsBySource", request.extraTargetsBySource(), view.extraTargetsBySource());
        readOnly("confidenceCap", request.confidenceCap(), view.confidenceCap());
        Optional.ofNullable(request.cardinality()).ifPresent(c -> {
            VocabularyConfig.PredicateOverride o = cfg.predicateOverrides().get(def.name());
            if (c != (o == null || o.cardinality() == null ? def.cardinality() : o.cardinality())) {
                throw GraphSpindleException.validation("the cardinality of core relationship type '" + def.name()
                        + "' can only be changed through the vocabulary configuration (predicateOverrides)");
            }
        });
        if (request.enabled() == null) {
            return cfg;
        }
        List<String> disabled = new ArrayList<>(cfg.disabledPredicates());
        if (request.enabled()) {
            disabled.remove(def.name());
        } else if (!disabled.contains(def.name())) {
            disabled.add(def.name());
        }
        Map<String, VocabularyConfig.PredicateOverride> overrides = new LinkedHashMap<>(cfg.predicateOverrides());
        if (!request.enabled()) {
            // an override of a disabled predicate is invalid (SCHEMA §11); it is dropped with the predicate
            overrides.remove(def.name());
        }
        return withOverrides(cfg.withDisabledPredicates(disabled), overrides);
    }

    // ---- views ------------------------------------------------------------------------------

    private List<NodeType> nodeTypes(VocabularyConfig cfg) {
        BrainVocabulary actual = registry.resolveUnchecked(cfg);
        // all core types enabled, so disabled core types still show their properties
        BrainVocabulary all = registry.resolveUnchecked(cfg.withEnabledEntityTypes(null));
        List<NodeType> out = new ArrayList<>();
        for (EntityTypeDef t : all.entityTypes()) {
            out.add(new NodeType(t.name(), t.description(), t.synonyms(), t.examples(), t.compatibilityGroup(),
                    t.core(), actual.isEnabledType(t.name()),
                    all.attributesOf(t.name()).stream().map(PropertyDefinition::of).toList()));
        }
        return out;
    }

    private List<RelationshipType> relationshipTypes(VocabularyConfig cfg) {
        BrainVocabulary actual = registry.resolveUnchecked(cfg);
        BrainVocabulary all = registry.resolveUnchecked(cfg.withEnabledEntityTypes(null));
        List<RelationshipType> out = new ArrayList<>();
        for (RelationPredicateDef core : registry.profile().relationPredicates()) {
            boolean enabled = !cfg.disabledPredicates().contains(core.name());
            RelationPredicateDef shown = actual.relation(core.name()).or(() -> all.relation(core.name()))
                    .orElse(core);
            out.add(RelationshipType.of(shown, enabled));
        }
        for (CustomRelationPredicate c : cfg.customRelationPredicates()) {
            all.relation(c.name()).ifPresent(d -> out.add(RelationshipType.of(d, true)));
        }
        return out;
    }

    // ---- helpers ----------------------------------------------------------------------------

    private Brain mutate(String brainId, Long expectedVersion, UnaryOperator<VocabularyConfig> change) {
        return brains.mutateVocabulary(brainId, expectedVersion, change);
    }

    private static List<CustomAttribute> customAttributes(List<PropertyDefinition> properties, boolean create) {
        if (properties == null) {
            return List.of();
        }
        List<CustomAttribute> out = new ArrayList<>();
        for (int i = 0; i < properties.size(); i++) {
            PropertyDefinition p = properties.get(i);
            if (p == null) {
                throw GraphSpindleException.validation("properties[" + i + "] is null");
            }
            if (p.type() == null) {
                throw GraphSpindleException.validation("properties[" + i + "].type is required");
            }
            if (p.description() == null || p.description().isBlank()) {
                throw GraphSpindleException.validation("properties[" + i + "].description is required");
            }
            out.add(new CustomAttribute(p.name(), p.type(), p.maxLength(), p.values(), p.min(), p.max(),
                    p.description().strip(), p.cardinality(), p.required(), p.synonyms()));
        }
        return out;
    }

    private static VocabularyConfig renameTypeReferences(VocabularyConfig cfg, String from, String to) {
        UnaryOperator<List<String>> rename = l -> l == null ? null
                : l.stream().map(t -> t.equals(from) ? to : t).toList();
        List<CustomRelationPredicate> rels = cfg.customRelationPredicates().stream()
                .map(r -> new CustomRelationPredicate(r.name(), r.description(), rename.apply(r.sourceTypes()),
                        rename.apply(r.targetTypes()), r.cardinality(), r.synonyms())).toList();
        List<CustomAttributePredicate> attrs = cfg.customAttributePredicates().stream()
                .map(a -> withTypes(a, rename.apply(a.entityTypes()))).toList();
        return cfg.withCustomRelationPredicates(rels).withCustomAttributePredicates(attrs)
                .withEnabledEntityTypes(rename.apply(cfg.enabledEntityTypes()));
    }

    private static CustomAttributePredicate withTypes(CustomAttributePredicate a, List<String> types) {
        return new CustomAttributePredicate(a.name(), types, a.valueType(), a.maxLength(), a.values(), a.min(),
                a.max(), a.description(), a.cardinality(), a.required(), a.synonyms());
    }

    private static VocabularyConfig withOverrides(VocabularyConfig cfg,
            Map<String, VocabularyConfig.PredicateOverride> overrides) {
        return new VocabularyConfig(cfg.profile(), cfg.enabledEntityTypes(), cfg.disabledPredicates(), overrides,
                cfg.customEntityTypes(), cfg.customRelationPredicates(), cfg.customAttributePredicates());
    }

    private static void coreOnlyFieldsAbsent(RelationshipType r) {
        if (Boolean.TRUE.equals(r.targetSameAsSource())
                || (r.extraTargetsBySource() != null && !r.extraTargetsBySource().isEmpty())
                || r.confidenceCap() != null) {
            throw GraphSpindleException.validation("targetSameAsSource, extraTargetsBySource and confidenceCap are "
                    + "only available for core relationship types (YAML)");
        }
    }

    private static void readOnly(String field, Object given, Object core) {
        if (given == null) {
            return;
        }
        Object g = given instanceof String s ? s.strip() : given;
        if (!Objects.equals(g, core)) {
            throw GraphSpindleException.validation("'" + field + "' of a core type is defined in the YAML vocabulary "
                    + "and is read-only through the API (only 'enabled' and extra node-type properties can change)");
        }
    }

    private static void requireBody(Object request) {
        if (request == null) {
            throw GraphSpindleException.validation("request body is required");
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw GraphSpindleException.validation("name is required");
        }
        return name.strip();
    }

    private static String requireTypeName(String name) {
        String n = requireName(name);
        if (!VocabularyConfigValidator.isValidTypeName(n)) {
            throw GraphSpindleException.validation("node type name must match "
                    + VocabularyConfigValidator.typeNamePattern());
        }
        return n;
    }

    private static String requireRelationName(String name) {
        String n = requireName(name);
        if (!VocabularyConfigValidator.isValidRelationName(n)) {
            throw GraphSpindleException.validation("relationship type name must match "
                    + VocabularyConfigValidator.relationNamePattern());
        }
        return n;
    }

    private static void requireDescription(String description) {
        if (description == null || description.isBlank()) {
            throw GraphSpindleException.validation("description is required");
        }
    }

    private static <T> T find(List<T> items, java.util.function.Function<T, String> name, String wanted,
            String what) {
        return items.stream().filter(i -> name.apply(i).equals(wanted)).findFirst()
                .orElseThrow(() -> GraphSpindleException.notFound(what, Normalizer.truncate(String.valueOf(wanted),
                        64)));
    }
}
