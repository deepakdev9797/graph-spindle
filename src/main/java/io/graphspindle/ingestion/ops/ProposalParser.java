package io.graphspindle.ingestion.ops;

import io.graphspindle.common.Limits;
import io.graphspindle.common.Polarity;
import io.graphspindle.common.Untrusted;
import io.graphspindle.ingestion.validation.RejectionCode;
import io.graphspindle.ingestion.validation.ValidationIssue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses raw reconcile-stage model output into a {@link ParsedProposal} (ARCHITECTURE §4.3).
 *
 * <p>Lenient but safe:
 * <ul>
 *   <li>Markdown code fences and surrounding prose are ignored; the first JSON object that has an
 *       {@code operations} array is used.</li>
 *   <li>Raw output, string lengths, nesting depth and the operations array are bounded, and no
 *       regular expressions run on the raw output, so huge or adversarial input is cheap to refuse.</li>
 *   <li>Duplicate JSON keys make the output malformed (ambiguous).</li>
 *   <li>Unknown envelope properties are recorded and ignored. An operation with an unknown op, an
 *       unknown property or a structurally wrong field is dropped with a {@link ValidationIssue};
 *       the rest of the proposal survives.</li>
 * </ul>
 * Semantic checks (vocabulary, handles, evidence, lengths) are {@code OperationValidator}'s job.
 *
 * <p>Uses its own Jackson 3 mapper rather than Boot's so the safety settings cannot be changed by
 * application configuration (DECISIONS ADR-016).
 */
public final class ProposalParser {

    /** Maximum JSON object start positions tried when the output contains prose with braces. */
    static final int MAX_START_ATTEMPTS = 8;
    static final int MAX_NESTING_DEPTH = 32;

    private static final Set<String> ENVELOPE_FIELDS = Set.of("summary", "operations");
    private static final Set<String> TARGET_FIELDS = Set.of("ref", "id");
    private static final Set<String> CLAIM_FIELDS = Set.of("subject", "predicate", "object", "value", "polarity",
            "statement", "evidence", "confidence", "validFrom", "validTo", "sources");
    private static final Map<String, Set<String>> OP_FIELDS = Map.of(
            CreateEntity.OP, Set.of("op", "ref", "type", "name", "description", "aliases"),
            UpdateEntity.OP, Set.of("op", "target", "description", "reason"),
            AddAlias.OP, Set.of("op", "target", "alias"),
            AssertClaim.OP, union(CLAIM_FIELDS, Set.of("op")),
            SupersedeClaim.OP, Set.of("op", "claimId", "explicitCorrection", "reason", "replacement"),
            RetractClaim.OP, Set.of("op", "claimId", "reason", "evidence"),
            FlagConflict.OP, Set.of("op", "claimId", "incoming", "description"),
            ProposeMerge.OP, Set.of("op", "a", "b", "reason"),
            LinkSource.OP, Set.of("op", "source", "target"));

    private final Limits limits;
    private final JsonMapper mapper;

    public ProposalParser() {
        this(Limits.defaults());
    }

    public ProposalParser(Limits limits) {
        this.limits = limits;
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(MAX_NESTING_DEPTH)
                        .maxStringLength(limits.maxModelOutputChars())
                        .maxDocumentLength(limits.maxModelOutputChars())
                        .maxNumberLength(64)
                        .maxNameLength(200)
                        .build())
                .build();
        this.mapper = JsonMapper.builder(factory)
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    /**
     * @throws MalformedModelOutputException if no usable proposal object can be read
     */
    public ParsedProposal parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new MalformedModelOutputException("model output is empty");
        }
        if (raw.length() > limits.maxModelOutputChars()) {
            throw new MalformedModelOutputException("model output exceeds " + limits.maxModelOutputChars()
                    + " characters");
        }
        JsonNode root = locateEnvelope(raw);
        return map(root);
    }

    // ---------------------------------------------------------------- locating the JSON object

    private JsonNode locateEnvelope(String raw) {
        String fenced = fencedBlock(raw);
        if (fenced != null) {
            JsonNode node = tryFrom(fenced, fenced.indexOf('{'));
            if (isEnvelope(node)) {
                return node;
            }
        }
        int from = raw.indexOf('{');
        for (int attempt = 0; from >= 0 && attempt < MAX_START_ATTEMPTS; attempt++) {
            JsonNode node = tryFrom(raw, from);
            if (isEnvelope(node)) {
                return node;
            }
            from = raw.indexOf('{', from + 1);
        }
        throw new MalformedModelOutputException("no JSON object with an operations array found");
    }

    /** Content of the first markdown code fence, or null. A missing closing fence takes the rest. */
    private static String fencedBlock(String raw) {
        int open = raw.indexOf("```");
        if (open < 0) {
            return null;
        }
        int lineEnd = raw.indexOf('\n', open + 3);
        if (lineEnd < 0) {
            return null;
        }
        int close = raw.indexOf("```", lineEnd + 1);
        return close < 0 ? raw.substring(lineEnd + 1) : raw.substring(lineEnd + 1, close);
    }

    private JsonNode tryFrom(String text, int start) {
        if (start < 0) {
            return null;
        }
        try (JsonParser parser = mapper.createParser(text.substring(start))) {
            // reads exactly one JSON value; trailing prose is ignored
            return mapper.readTree(parser);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static boolean isEnvelope(JsonNode node) {
        return node != null && node.isObject() && node.has("operations");
    }

    // ---------------------------------------------------------------- mapping

    private ParsedProposal map(JsonNode root) {
        List<String> unknownFields = new ArrayList<>();
        for (String name : root.propertyNames()) {
            if (!ENVELOPE_FIELDS.contains(name)) {
                unknownFields.add(Untrusted.preview(name));
            }
        }
        JsonNode opsNode = root.get("operations");
        if (!opsNode.isArray()) {
            throw new MalformedModelOutputException("operations must be an array");
        }
        if (opsNode.size() > limits.maxParsedOperations()) {
            throw new MalformedModelOutputException("operations array has " + opsNode.size()
                    + " entries; at most " + limits.maxParsedOperations() + " are read");
        }
        JsonNode summaryNode = root.get("summary");
        String summary = summaryNode != null && summaryNode.isString() ? summaryNode.stringValue() : null;
        if (summaryNode != null && !summaryNode.isNull() && !summaryNode.isString()) {
            unknownFields.add("summary(not a string)");
        }

        List<MutationOperation> ops = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        List<ValidationIssue> rejected = new ArrayList<>();
        for (int i = 0; i < opsNode.size(); i++) {
            JsonNode node = opsNode.get(i);
            String opName = null;
            try {
                if (!node.isObject()) {
                    throw new FieldError(RejectionCode.INVALID_VALUE, "operation must be a JSON object");
                }
                JsonNode opField = node.get("op");
                if (opField == null || !opField.isString()) {
                    throw new FieldError(RejectionCode.UNKNOWN_OP, "operation has no op string");
                }
                String candidate = opField.stringValue().strip().toLowerCase(Locale.ROOT);
                if (!OP_FIELDS.containsKey(candidate)) {
                    throw new FieldError(RejectionCode.UNKNOWN_OP,
                            "unknown op '" + Untrusted.preview(opField.stringValue()) + "'");
                }
                opName = candidate;
                Op reader = new Op(node, "operations[" + i + "]", unknownFields);
                reader.checkFields(OP_FIELDS.get(opName));
                ops.add(reader.read(opName));
                indexes.add(i);
            } catch (FieldError e) {
                rejected.add(new ValidationIssue(i, opName, e.code, e.getMessage()));
            }
        }
        return new ParsedProposal(new Proposal(summary, ops), indexes, rejected, unknownFields);
    }

    /** Reads one operation object; throws {@link FieldError} on structural problems. */
    private record Op(JsonNode node, String path, List<String> unknownFields) {

        void checkFields(Set<String> allowed) {
            checkFields(node, path, allowed);
        }

        void checkFields(JsonNode n, String p, Set<String> allowed) {
            List<String> unknown = new ArrayList<>();
            for (String name : n.propertyNames()) {
                if (!allowed.contains(name)) {
                    unknown.add(Untrusted.preview(name));
                }
            }
            if (!unknown.isEmpty()) {
                unknown.forEach(u -> unknownFields.add(p + "." + u));
                throw new FieldError(RejectionCode.UNKNOWN_FIELD, "unknown field(s) " + unknown + " at " + p);
            }
        }

        MutationOperation read(String op) {
            return switch (op) {
                case CreateEntity.OP -> new CreateEntity(reqString(node, "ref"), reqString(node, "type"),
                        reqString(node, "name"), optString(node, "description"), stringList(node, "aliases"));
                case UpdateEntity.OP -> new UpdateEntity(target(node, "target"), reqString(node, "description"),
                        optString(node, "reason"));
                case AddAlias.OP -> new AddAlias(target(node, "target"), reqString(node, "alias"));
                case AssertClaim.OP -> claim(node, path);
                case SupersedeClaim.OP -> new SupersedeClaim(reqString(node, "claimId"),
                        reqBoolean(node, "explicitCorrection"), reqString(node, "reason"),
                        claimBody(node, "replacement"));
                case RetractClaim.OP -> new RetractClaim(reqString(node, "claimId"), reqString(node, "reason"),
                        reqString(node, "evidence"));
                case FlagConflict.OP -> new FlagConflict(reqString(node, "claimId"), claimBody(node, "incoming"),
                        reqString(node, "description"));
                case ProposeMerge.OP -> new ProposeMerge(target(node, "a"), target(node, "b"),
                        reqString(node, "reason"));
                case LinkSource.OP -> new LinkSource(reqInt(node, "source"), target(node, "target"));
                default -> throw new FieldError(RejectionCode.UNKNOWN_OP, "unknown op");
            };
        }

        private AssertClaim claimBody(JsonNode parent, String field) {
            JsonNode body = parent.get(field);
            if (body == null || body.isNull()) {
                throw new FieldError(RejectionCode.INVALID_VALUE, "field '" + field + "' is required");
            }
            if (!body.isObject()) {
                throw new FieldError(RejectionCode.INVALID_VALUE, "field '" + field + "' must be an object");
            }
            JsonNode op = body.get("op");
            if (op != null && !(op.isString() && AssertClaim.OP.equals(op.stringValue()))) {
                throw new FieldError(RejectionCode.INVALID_VALUE, "field '" + field + "' must be an assert_claim body");
            }
            checkFields(body, path + "." + field, union(CLAIM_FIELDS, Set.of("op")));
            return claim(body, path + "." + field);
        }

        private AssertClaim claim(JsonNode n, String p) {
            EntityTarget subject = target(n, "subject");
            String predicate = reqString(n, "predicate");
            JsonNode objectNode = n.get("object");
            JsonNode valueNode = n.get("value");
            boolean hasObject = objectNode != null && !objectNode.isNull();
            boolean hasValue = valueNode != null && !valueNode.isNull();
            if (hasObject == hasValue) {
                throw new FieldError(RejectionCode.INVALID_VALUE, "exactly one of 'object' or 'value' is required");
            }
            EntityTarget object = hasObject ? target(n, "object") : null;
            String value = null;
            if (hasValue) {
                if (valueNode.isString()) {
                    value = valueNode.stringValue();
                } else if (valueNode.isNumber() || valueNode.isBoolean()) {
                    value = valueNode.asString();
                } else {
                    throw new FieldError(RejectionCode.INVALID_VALUE, "'value' must be a string");
                }
            }
            return new AssertClaim(subject, predicate, object, value, polarity(n), reqString(n, "statement"),
                    reqString(n, "evidence"), reqNumber(n, "confidence"), optString(n, "validFrom"),
                    optString(n, "validTo"), intList(n, "sources"));
        }

        private EntityTarget target(JsonNode parent, String field) {
            JsonNode t = parent.get(field);
            if (t == null || t.isNull()) {
                throw new FieldError(RejectionCode.INVALID_VALUE, "field '" + field + "' is required");
            }
            if (!t.isObject()) {
                throw new FieldError(RejectionCode.INVALID_VALUE,
                        "field '" + field + "' must be an object {\"ref\":…} or {\"id\":…}");
            }
            checkFields(t, path + "." + field, TARGET_FIELDS);
            boolean hasRef = t.has("ref") && !t.get("ref").isNull();
            boolean hasId = t.has("id") && !t.get("id").isNull();
            if (hasRef == hasId) {
                throw new FieldError(RejectionCode.INVALID_VALUE,
                        "field '" + field + "' needs exactly one of 'ref' or 'id'");
            }
            return hasRef ? EntityTarget.ref(reqString(t, "ref")) : EntityTarget.handle(reqString(t, "id"));
        }

        private static Polarity polarity(JsonNode n) {
            String p = optString(n, "polarity");
            if (p == null) {
                return Polarity.POSITIVE;
            }
            return switch (p.strip().toUpperCase(Locale.ROOT)) {
                case "POSITIVE" -> Polarity.POSITIVE;
                case "NEGATIVE" -> Polarity.NEGATIVE;
                default -> throw new FieldError(RejectionCode.INVALID_VALUE, "polarity must be POSITIVE or NEGATIVE");
            };
        }

        private static String reqString(JsonNode n, String field) {
            String s = optString(n, field);
            if (s == null) {
                throw new FieldError(RejectionCode.INVALID_VALUE, "field '" + field + "' is required");
            }
            return s;
        }

        private static String optString(JsonNode n, String field) {
            JsonNode v = n.get(field);
            if (v == null || v.isNull()) {
                return null;
            }
            if (!v.isString()) {
                throw new FieldError(RejectionCode.INVALID_VALUE, "field '" + field + "' must be a string");
            }
            return v.stringValue();
        }

        private static boolean reqBoolean(JsonNode n, String field) {
            JsonNode v = n.get(field);
            if (v != null && v.isBoolean()) {
                return v.booleanValue();
            }
            if (v != null && v.isString()) {
                String s = v.stringValue().strip().toLowerCase(Locale.ROOT);
                if (s.equals("true") || s.equals("false")) {
                    return Boolean.parseBoolean(s);
                }
            }
            throw new FieldError(RejectionCode.INVALID_VALUE, "field '" + field + "' must be true or false");
        }

        private static double reqNumber(JsonNode n, String field) {
            JsonNode v = n.get(field);
            BigDecimal d = null;
            if (v != null && v.isNumber()) {
                d = v.decimalValue();
            } else if (v != null && v.isString() && v.stringValue().length() <= 32) {
                try {
                    d = new BigDecimal(v.stringValue().strip());
                } catch (NumberFormatException e) {
                    d = null;
                }
            }
            if (d == null) {
                throw new FieldError(RejectionCode.INVALID_VALUE, "field '" + field + "' must be a number");
            }
            return d.doubleValue();
        }

        private static int reqInt(JsonNode n, String field) {
            JsonNode v = n.get(field);
            if (v == null || !v.isIntegralNumber() || !v.canConvertToInt()) {
                throw new FieldError(RejectionCode.INVALID_VALUE, "field '" + field + "' must be an integer");
            }
            return v.intValue();
        }

        private static List<String> stringList(JsonNode n, String field) {
            JsonNode v = n.get(field);
            if (v == null || v.isNull()) {
                return List.of();
            }
            if (!v.isArray()) {
                throw new FieldError(RejectionCode.INVALID_VALUE, "field '" + field + "' must be an array of strings");
            }
            List<String> out = new ArrayList<>();
            for (JsonNode e : v) {
                if (!e.isString()) {
                    throw new FieldError(RejectionCode.INVALID_VALUE,
                            "field '" + field + "' must be an array of strings");
                }
                out.add(e.stringValue());
            }
            return out;
        }

        private static List<Integer> intList(JsonNode n, String field) {
            JsonNode v = n.get(field);
            if (v == null || v.isNull()) {
                return List.of();
            }
            if (!v.isArray()) {
                throw new FieldError(RejectionCode.INVALID_VALUE, "field '" + field + "' must be an array of integers");
            }
            List<Integer> out = new ArrayList<>();
            for (JsonNode e : v) {
                if (!e.isIntegralNumber() || !e.canConvertToInt()) {
                    throw new FieldError(RejectionCode.INVALID_VALUE,
                            "field '" + field + "' must be an array of integers");
                }
                out.add(e.intValue());
            }
            return out;
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        java.util.HashSet<String> s = new java.util.HashSet<>(a);
        s.addAll(b);
        return Set.copyOf(s);
    }

    /** Structural problem in one operation. */
    private static final class FieldError extends RuntimeException {

        private final RejectionCode code;

        FieldError(RejectionCode code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }
}
