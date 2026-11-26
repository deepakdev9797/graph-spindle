package io.graphspindle.vocabulary;

import io.graphspindle.common.Limits;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders the JSON Schema (draft 2020-12) of the reconcile-stage proposal (ARCHITECTURE §4) from a
 * {@link BrainVocabulary}, with enum lists for entity types and predicates. The schema is placed in
 * the prompt; {@code ProposalParser} and {@code OperationValidator} remain the source of truth.
 */
public final class OutputSchemaRenderer {

    public static final String REF_PATTERN = "^[a-z][a-z0-9_]{0,15}$";

    private final JsonMapper mapper = JsonMapper.builder().build();

    public String render(BrainVocabulary vocabulary, Limits limits) {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(renderNode(vocabulary, limits));
    }

    public JsonNode renderNode(BrainVocabulary vocabulary, Limits limits) {
        ObjectNode root = mapper.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("title", "GraphSpindle proposal (" + vocabulary.profile() + ")");
        root.put("type", "object");
        root.put("additionalProperties", false);
        root.putArray("required").add("operations");
        ObjectNode props = root.putObject("properties");
        props.set("summary", string(1000));
        ObjectNode ops = props.putObject("operations");
        ops.put("type", "array");
        ops.put("maxItems", limits.maxOperations());
        ArrayNode oneOf = ops.putObject("items").putArray("oneOf");

        List<String> types = new ArrayList<>(vocabulary.entityTypeNames());
        Set<String> relations = new LinkedHashSet<>();
        vocabulary.relationPredicates().forEach(p -> relations.add(p.name()));
        Set<String> attributes = new LinkedHashSet<>(vocabulary.attributeNames());
        // nested claims (replacement, incoming) refer to these definitions
        ObjectNode defs = root.putObject("$defs");
        defs.set(RELATION_CLAIM, claimBody(relations, true, limits));
        defs.set(ATTRIBUTE_CLAIM, claimBody(attributes, false, limits));

        oneOf.add(op("create_entity", List.of("ref", "type", "name"), p -> {
            p.set("ref", pattern(REF_PATTERN));
            p.set("type", enumOf(types));
            p.set("name", string(200));
            p.set("description", string(1000));
            ObjectNode aliases = p.putObject("aliases");
            aliases.put("type", "array");
            aliases.put("maxItems", limits.maxAliasesPerEntity());
            aliases.set("items", string(200));
        }));
        oneOf.add(op("update_entity", List.of("target", "description"), p -> {
            p.set("target", entityTarget());
            p.set("description", string(1000));
            p.set("reason", string(300));
        }));
        oneOf.add(op("add_alias", List.of("target", "alias"), p -> {
            p.set("target", entityTarget());
            p.set("alias", string(200));
        }));
        // two complete alternatives instead of one object with a sibling "oneOf" of bare "required"
        // lists: grammar-based structured output (Ollama/llama.cpp) reads such a oneOf as "any JSON
        // value", which let gemma4 close a claim early and emit its remaining fields as stray array
        // items (live smoke, ADR-030)
        for (ObjectNode assertClaim : List.of(claimBody(relations, true, limits), claimBody(attributes, false, limits))) {
            ObjectNode withOp = mapper.createObjectNode();
            withOp.put("type", "object");
            withOp.put("additionalProperties", false);
            ArrayNode req = withOp.putArray("required").add("op");
            assertClaim.get("required").forEach(req::add);
            ObjectNode claimProps = withOp.putObject("properties");
            claimProps.set("op", constant("assert_claim"));
            assertClaim.get("properties").properties().forEach(e -> claimProps.set(e.getKey(), e.getValue()));
            oneOf.add(withOp);
        }
        oneOf.add(op("supersede_claim", List.of("claimId", "explicitCorrection", "reason", "replacement"), p -> {
            p.set("claimId", string(20));
            p.putObject("explicitCorrection").put("type", "boolean");
            p.set("reason", string(300));
            p.set("replacement", claimRef());
        }));
        oneOf.add(op("retract_claim", List.of("claimId", "reason", "evidence"), p -> {
            p.set("claimId", string(20));
            p.set("reason", string(300));
            p.set("evidence", string(300));
        }));
        oneOf.add(op("flag_conflict", List.of("claimId", "incoming", "description"), p -> {
            p.set("claimId", string(20));
            p.set("incoming", claimRef());
            p.set("description", string(500));
        }));
        oneOf.add(op("propose_merge", List.of("a", "b", "reason"), p -> {
            p.set("a", entityTarget());
            p.set("b", entityTarget());
            p.set("reason", string(300));
        }));
        oneOf.add(op("link_source", List.of("source", "target"), p -> {
            ObjectNode source = p.putObject("source");
            source.put("type", "integer");
            source.put("minimum", 0);
            source.put("maximum", Math.max(0, limits.maxSources() - 1));
            p.set("target", entityTarget());
        }));
        return root;
    }

    static final String RELATION_CLAIM = "relationClaim";
    static final String ATTRIBUTE_CLAIM = "attributeClaim";

    /** A nested claim: a relation claim (with {@code object}) or an attribute claim (with {@code value}). */
    private ObjectNode claimRef() {
        ObjectNode node = mapper.createObjectNode();
        ArrayNode oneOf = node.putArray("oneOf");
        oneOf.addObject().put("$ref", "#/$defs/" + RELATION_CLAIM);
        oneOf.addObject().put("$ref", "#/$defs/" + ATTRIBUTE_CLAIM);
        return node;
    }

    /** A complete claim object: relation claims require {@code object}, attribute claims {@code value}. */
    private ObjectNode claimBody(Set<String> predicates, boolean relation, Limits limits) {
        ObjectNode body = mapper.createObjectNode();
        body.put("type", "object");
        body.put("additionalProperties", false);
        ArrayNode required = body.putArray("required");
        List.of("subject", "predicate", relation ? "object" : "value", "statement", "evidence", "confidence")
                .forEach(required::add);
        ObjectNode p = body.putObject("properties");
        p.set("subject", entityTarget());
        p.set("predicate", enumOf(new ArrayList<>(predicates)));
        if (relation) {
            p.set("object", entityTarget());
        } else {
            // the longest literal any attribute accepts (URL values may have up to 2000 characters)
            ObjectNode value = string(AttributeValues.MAX_VALUE_LENGTH);
            value.put("description", "literal value for attribute predicates");
            p.set("value", value);
        }
        p.set("polarity", enumOf(List.of("POSITIVE", "NEGATIVE")));
        p.set("statement", string(500));
        ObjectNode evidence = string(300);
        evidence.put("description", "a verbatim quote from the input");
        p.set("evidence", evidence);
        ObjectNode confidence = p.putObject("confidence");
        confidence.put("type", "number");
        confidence.put("minimum", 0);
        confidence.put("maximum", 1);
        p.set("validFrom", pattern("^\\d{4}(-\\d{2}(-\\d{2})?)?$"));
        p.set("validTo", pattern("^\\d{4}(-\\d{2}(-\\d{2})?)?$"));
        ObjectNode sources = p.putObject("sources");
        sources.put("type", "array");
        sources.put("maxItems", limits.maxSources());
        ObjectNode item = sources.putObject("items");
        item.put("type", "integer");
        item.put("minimum", 0);
        return body;
    }

    private ObjectNode op(String name, List<String> required, java.util.function.Consumer<ObjectNode> fields) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "object");
        node.put("additionalProperties", false);
        ArrayNode req = node.putArray("required");
        req.add("op");
        required.forEach(req::add);
        ObjectNode props = node.putObject("properties");
        props.set("op", constant(name));
        fields.accept(props);
        return node;
    }

    private ObjectNode entityTarget() {
        ObjectNode node = mapper.createObjectNode();
        ArrayNode oneOf = node.putArray("oneOf");
        ObjectNode ref = oneOf.addObject();
        ref.put("type", "object");
        ref.put("additionalProperties", false);
        ref.putArray("required").add("ref");
        ref.putObject("properties").set("ref", pattern(REF_PATTERN));
        ObjectNode id = oneOf.addObject();
        id.put("type", "object");
        id.put("additionalProperties", false);
        id.putArray("required").add("id");
        ObjectNode idProp = string(20);
        idProp.put("description", "candidate handle such as k1, or P0 for the speaker");
        id.putObject("properties").set("id", idProp);
        return node;
    }

    private ObjectNode string(int maxLength) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "string");
        node.put("maxLength", maxLength);
        return node;
    }

    private ObjectNode pattern(String regex) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "string");
        node.put("pattern", regex);
        return node;
    }

    private ObjectNode constant(String value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("const", value);
        return node;
    }

    private ObjectNode enumOf(List<String> values) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "string");
        ArrayNode e = node.putArray("enum");
        values.forEach(e::add);
        return node;
    }
}
