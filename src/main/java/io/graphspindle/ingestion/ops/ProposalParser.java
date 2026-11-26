package io.graphspindle.ingestion.ops;

import io.graphspindle.common.Limits;
import io.graphspindle.common.Polarity;
import io.graphspindle.common.Untrusted;
import io.graphspindle.ingestion.validation.Adjustment;
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
        return parse(raw, Set.of());
    }

    /**
     * Like {@link #parse(String)}, but a {@code create_entity} property named after one of
     * {@code attributeNames} (a vocabulary attribute predicate such as {@code category}) is dropped
     * with an {@code ATTRIBUTE_FIELD_DROPPED} adjustment instead of rejecting the whole operation
     * (ADR-028). It is still recorded in {@code unknownFields}. Other unknown fields stay rejections.
     *
     * @throws MalformedModelOutputException if no usable proposal object can be read
     */
    public ParsedProposal parse(String raw, Set<String> attributeNames) {
        if (raw == null || raw.isBlank()) {
            throw new MalformedModelOutputException("model output is empty");
        }
        if (raw.length() > limits.maxModelOutputChars()) {
            throw new MalformedModelOutputException("model output exceeds " + limits.maxModelOutputChars()
                    + " characters");
        }
        JsonNode root = locateEnvelope(raw);
        return map(root, attributeNames == null ? Set.of() : attributeNames);
    }

    // ---------------------------------------------------------------- locating the JSON object

    private JsonNode locateEnvelope(String raw) {
        String[] firstError = new String[1];
        String fenced = fencedBlock(raw);
        if (fenced != null) {
            JsonNode node = tryFrom(fenced, fenced.indexOf('{'), firstError);
            if (isEnvelope(node)) {
                return node;
            }
        }
        int from = raw.indexOf('{');
        for (int attempt = 0; from >= 0 && attempt < MAX_START_ATTEMPTS; attempt++) {
            JsonNode node = tryFrom(raw, from, firstError);
            if (isEnvelope(node)) {
                return node;
            }
            // Only top-level objects are candidates: an object nested inside a span that was already
            // tried (parsed or rejected, e.g. for duplicate keys) must not rescue it (ADR-021).
            int end = objectEnd(raw, from);
            from = raw.indexOf('{', end < 0 ? from + 1 : end + 1);
        }
        if (raw.indexOf('{') < 0) {
            throw new MalformedModelOutputException("no JSON object with an operations array found: the output"
                    + " contains no '{'");
        }
        throw new MalformedModelOutputException("no JSON object with an operations array found"
                + (firstError[0] == null ? "" : "; first JSON error " + firstError[0]));
    }

    /**
     * Index of the brace closing the object that starts at {@code start}, honouring JSON strings
     * and escapes; -1 when it is never closed (then the brace was probably prose). Linear.
     */
    static int objectEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
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

    private JsonNode tryFrom(String text, int start, String[] firstError) {
        if (start < 0) {
            return null;
        }
        try (JsonParser parser = mapper.createParser(text.substring(start))) {
            // reads exactly one JSON value; trailing prose is ignored
            return mapper.readTree(parser);
        } catch (JacksonException e) {
            if (firstError[0] == null) {
                firstError[0] = describe(text, start, e);
            }
            return null;
        }
    }

    /**
     * Where a JSON read failed: line and column in {@code text} (1-based), Jackson's message and the
     * text just before the fault, so the repair prompt can point at it (ADR-028). The excerpt is short
     * and only ever goes back to the model that produced it.
     */
    static String describe(String text, int start, JacksonException e) {
        var loc = e.getLocation();
        String msg = Untrusted.preview(e.getOriginalMessage() == null ? e.getClass().getSimpleName()
                : e.getOriginalMessage());
        if (loc == null || loc.getCharOffset() < 0) {
            return "(" + msg + ")";
        }
        int at = (int) Math.min(start + loc.getCharOffset(), text.length());
        int line = 1;
        int col = 1;
        for (int i = 0; i < at; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        String before = text.substring(Math.max(start, at - 40), at).replace('\n', ' ');
        return "at line " + line + " column " + col + " (offset " + at + "): " + msg + "; text before the error: \""
                + before + "\"";
    }

    private static boolean isEnvelope(JsonNode node) {
        return node != null && node.isObject() && node.has("operations");
    }

    // ---------------------------------------------------------------- mapping

    private ParsedProposal map(JsonNode root, Set<String> attributeNames) {
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
        List<Adjustment> adjustments = new ArrayList<>();
        Map<String, Integer> droppedRefs = new java.util.LinkedHashMap<>();
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
                List<String> dropped = CreateEntity.OP.equals(opName)
                        ? reader.checkFields(OP_FIELDS.get(opName), attributeNames)
                        : reader.checkFields(OP_FIELDS.get(opName), Set.of());
                ops.add(reader.read(opName));
                indexes.add(i);
                for (String d : dropped) {
                    adjustments.add(new Adjustment(i, opName, Adjustment.Kind.ATTRIBUTE_FIELD_DROPPED, "attribute field '"
                            + d + "' is not a create_entity field and was dropped; state it as an assert_claim"));
                }
            } catch (FieldError e) {
                rejected.add(new ValidationIssue(i, opName, e.code, e.getMessage()));
                if (CreateEntity.OP.equals(opName) && node.get("ref") != null && node.get("ref").isString()) {
                    droppedRefs.putIfAbsent(node.get("ref").stringValue(), i);
                }
            }
        }
        return new ParsedProposal(new Proposal(summary, ops), indexes, rejected, unknownFields, adjustments,
                droppedRefs);
    }

    /** Reads one operation object; throws {@link FieldError} on structural problems. */
    private record Op(JsonNode node, String path, List<String> unknownFields) {

        /** Checks the operation's fields; returns the tolerated ones (in {@code tolerated}) that were dropped. */
        List<String> checkFields(Set<String> allowed, Set<String> tolerated) {
            List<String> dropped = new ArrayList<>();
            List<String> unknown = new ArrayList<>();
            for (String name : node.propertyNames()) {
                if (allowed.contains(name)) {
                    continue;
                }
                unknownFields.add(path + "." + Untrusted.preview(name));
                if (tolerated.contains(name)) {
                    dropped.add(name);
                } else {
                    unknown.add(Untrusted.preview(name));
                }
            }
            if (!unknown.isEmpty()) {
                throw new FieldError(RejectionCode.UNKNOWN_FIELD, "unknown field(s) " + unknown + " at " + path);
            }
            return dropped;
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
