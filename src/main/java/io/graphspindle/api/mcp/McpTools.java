package io.graphspindle.api.mcp;

import io.graphspindle.api.rest.BrainDtos.BrainResponse;
import io.graphspindle.brain.BrainService;
import io.graphspindle.brain.VocabularyTypeService;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.conflict.ConflictService;
import io.graphspindle.conflict.ConflictService.Resolve;
import io.graphspindle.ingestion.IngestRequest;
import io.graphspindle.ingestion.IngestionService;
import io.graphspindle.knowledge.KnowledgeModel.NewClaim;
import io.graphspindle.knowledge.KnowledgeModel.Replacement;
import io.graphspindle.knowledge.KnowledgeModel.Retraction;
import io.graphspindle.knowledge.KnowledgeModel.Supersession;
import io.graphspindle.knowledge.KnowledgeService;
import io.graphspindle.retrieval.EntityReadService;
import io.graphspindle.retrieval.NeighborhoodService;
import io.graphspindle.retrieval.NodeViewService;
import io.graphspindle.retrieval.SearchService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The MCP tools (ARCHITECTURE §11, ADR-032). Each tool maps its arguments and makes exactly one call
 * to the application service its REST endpoint calls, with the same {@code Actor} and an explicit
 * {@code brainId} validated by the service, so REST and MCP enforce the same brain boundary and
 * authorization. No logic lives here. Admin operations (brain create/update/delete, vocabulary edits,
 * API keys) are REST-only.
 */
@Component
public class McpTools {

    static final String BRAIN = "Id of the brain to work in (see list_brains). Every brain is independent; "
            + "nothing crosses brains.";
    static final String NO_CYPHER = " You never need Cypher, labels or a schema: the server owns the graph.";

    private final BrainService brains;
    private final VocabularyTypeService types;
    private final IngestionService ingestions;
    private final SearchService search;
    private final EntityReadService reads;
    private final NodeViewService nodes;
    private final NeighborhoodService neighborhood;
    private final KnowledgeService knowledge;
    private final ConflictService conflicts;
    private final McpResults results;
    /** The application mapper: strict like REST (unknown properties fail). */
    private final JsonMapper json;

    public McpTools(BrainService brains, VocabularyTypeService types, IngestionService ingestions,
            SearchService search, EntityReadService reads, NodeViewService nodes, NeighborhoodService neighborhood,
            KnowledgeService knowledge, ConflictService conflicts, McpResults results, JsonMapper json) {
        this.json = json;
        this.brains = brains;
        this.types = types;
        this.ingestions = ingestions;
        this.search = search;
        this.reads = reads;
        this.nodes = nodes;
        this.neighborhood = neighborhood;
        this.knowledge = knowledge;
        this.conflicts = conflicts;
        this.results = results;
    }

    // ------------------------------------------------------------------ brains and vocabulary

    @McpTool(name = "list_brains", description = "List the brains (independent knowledge graphs) you can use."
            + NO_CYPHER, annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public CallToolResult listBrains(McpTransportContext ctx) {
        return results.call(() -> brains.list(McpCaller.actor(ctx)).stream().map(BrainResponse::of).toList());
    }

    @McpTool(name = "get_brain", description = "One brain with its configuration.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public CallToolResult getBrain(McpTransportContext ctx, @McpToolParam(description = BRAIN) String brainId) {
        return results.call(() -> BrainResponse.of(brains.get(McpCaller.actor(ctx), brainId)));
    }

    @McpTool(name = "list_node_types", description = "The kinds of things (entity types) this brain knows, with"
            + " descriptions and properties. Read-only; useful to understand search results." + NO_CYPHER,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public CallToolResult listNodeTypes(McpTransportContext ctx, @McpToolParam(description = BRAIN) String brainId) {
        return results.call(() -> types.nodeTypes(McpCaller.actor(ctx), brainId).value());
    }

    @McpTool(name = "list_relationship_types", description = "The relationships (predicates) this brain knows,"
            + " with descriptions and allowed entity types. Read-only." + NO_CYPHER,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public CallToolResult listRelationshipTypes(McpTransportContext ctx,
            @McpToolParam(description = BRAIN) String brainId) {
        return results.call(() -> types.relationshipTypes(McpCaller.actor(ctx), brainId).value());
    }

    // ------------------------------------------------------------------ ingestion

    @McpTool(name = "remember", description = "Remember unstructured information (a note, a conversation excerpt,"
            + " an observation, a summary of a document you read). The server's own LLM extracts entities and"
            + " facts, reconciles them with what the brain already knows, validates them and stores them with"
            + " provenance; contradictions become conflicts instead of overwriting. Just pass the text: you never"
            + " need Cypher, labels or a schema. Files and URLs are never fetched: pass a summary as a source."
            + " Use requestId to make retries idempotent.", annotations = @McpTool.McpAnnotations(
                    readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    public CallToolResult remember(McpTransportContext ctx,
            @McpToolParam(description = BRAIN) String brainId,
            @McpToolParam(description = "The information to remember (at most 32,000 characters)") String content,
            @McpToolParam(required = false, description = "CONVERSATION, NOTE (default), OBSERVATION,"
                    + " DOCUMENT_SUMMARY or OTHER") String contentType,
            @McpToolParam(required = false, description = "Client idempotency key, [A-Za-z0-9._:-]{1,100}")
            String requestId,
            @McpToolParam(required = false, description = "Where the information came from (never fetched):"
                    + " kind FILE|URL|MESSAGE|OTHER, locator, optional title, summary, mediaType (type/subtype),"
                    + " contentHash (hex or base64 digest)") List<IngestRequest.Source> sources,
            @McpToolParam(required = false, description = "Who said it (type Person or Agent, name); default the"
                    + " brain owner") IngestRequest.Speaker speaker,
            @McpToolParam(required = false, description = "When it happened, ISO-8601 instant") String occurredAt,
            @McpToolParam(required = false, description = "true returns the plan without storing anything")
            Boolean dryRun, McpSchema.CallToolRequest call) {
        return results.call(() -> {
            // sources and speaker are re-read strictly from the raw arguments, so a misspelled field is
            // VALIDATION_FAILED like REST instead of being dropped by the lenient tool-argument binding
            Map<String, Object> raw = call == null || call.arguments() == null ? Map.of() : call.arguments();
            List<IngestRequest.Source> strictSources = strict(raw.get("sources"),
                    new TypeReference<List<IngestRequest.Source>>() { }, "sources", sources);
            IngestRequest.Speaker strictSpeaker = strict(raw.get("speaker"),
                    new TypeReference<IngestRequest.Speaker>() { }, "speaker", speaker);
            return ingestions.ingest(McpCaller.actor(ctx), brainId, new IngestRequest(content, contentType,
                    requestId, strictSources, strictSpeaker, instant("occurredAt", occurredAt)),
                    Boolean.TRUE.equals(dryRun), false);
        });
    }

    private <T> T strict(Object raw, TypeReference<T> type, String name, T bound) {
        if (raw == null) {
            return bound;
        }
        try {
            return json.convertValue(raw, type);
        } catch (IllegalArgumentException | JacksonException e) {
            throw GraphSpindleException.validation(name + " is malformed or has unknown fields");
        }
    }

    @McpTool(name = "get_ingestion", description = "Status and result of an earlier remember call, by ingestionId"
            + " or by your requestId.", annotations = @McpTool.McpAnnotations(readOnlyHint = true,
                    destructiveHint = false))
    public CallToolResult getIngestion(McpTransportContext ctx, @McpToolParam(description = BRAIN) String brainId,
            @McpToolParam(required = false, description = "The ingestionId") String ingestionId,
            @McpToolParam(required = false, description = "The requestId you passed to remember") String requestId) {
        return results.call(() -> {
            if (ingestionId != null) {
                return ingestions.get(McpCaller.actor(ctx), brainId, ingestionId);
            }
            if (requestId == null || requestId.isBlank()) {
                throw GraphSpindleException.validation("ingestionId or requestId is required");
            }
            return ingestions.getByRequestId(McpCaller.actor(ctx), brainId, requestId);
        });
    }

    // ------------------------------------------------------------------ retrieval

    @McpTool(name = "search_knowledge", description = "Find entities by name, alias or the text of facts and"
            + " sources (exact alias matches first)." + NO_CYPHER, annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false))
    public CallToolResult searchKnowledge(McpTransportContext ctx, @McpToolParam(description = BRAIN) String brainId,
            @McpToolParam(description = "Words to search for") String query,
            @McpToolParam(required = false, description = "Only these entity types") List<String> types,
            @McpToolParam(required = false, description = "At most this many hits (1-50, default 10)") Integer limit) {
        return results.call(() -> search.search(McpCaller.actor(ctx), brainId, query, types, limit));
    }

    @McpTool(name = "list_entities", description = "List entities of the brain, optionally of one type"
            + " (keyset pagination: pass the previous page's next as cursor).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public CallToolResult listEntities(McpTransportContext ctx, @McpToolParam(description = BRAIN) String brainId,
            @McpToolParam(required = false, description = "Entity type, e.g. Project") String type,
            @McpToolParam(required = false, description = "ACTIVE (default), MERGED or ARCHIVED") String status,
            @McpToolParam(required = false, description = "next of the previous page") String cursor,
            @McpToolParam(required = false, description = "Page size (1-200, default 50)") Integer limit) {
        return results.call(() -> knowledge.listEntities(McpCaller.actor(ctx), brainId, type, status, cursor, limit));
    }

    @McpTool(name = "get_entity", description = "What the brain knows about one entity: its properties and"
            + " relationships; includeClaims adds the facts with their sources, disputes and a compact text;"
            + " asOf shows what the brain believed at that time." + NO_CYPHER,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public CallToolResult getEntity(McpTransportContext ctx, @McpToolParam(description = BRAIN) String brainId,
            @McpToolParam(description = "Entity id (from search_knowledge)") String entityId,
            @McpToolParam(required = false, description = "true adds claims, sources and disputes")
            Boolean includeClaims,
            @McpToolParam(required = false, description = "Record-time point: ISO date (end of that day UTC) or"
                    + " date-time") String asOf,
            @McpToolParam(required = false, description = "At most this many claims (1-100, default 30)")
            Integer maxClaims) {
        return results.call(() -> reads.read(McpCaller.actor(ctx), brainId, entityId, NodeViewService.parseAsOf(asOf),
                Boolean.TRUE.equals(includeClaims), maxClaims));
    }

    @McpTool(name = "get_entity_history", description = "Chronological changes of an entity's properties and"
            + " relationships, with the claim, origin, ingestion and sources of each change.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public CallToolResult getEntityHistory(McpTransportContext ctx, @McpToolParam(description = BRAIN) String brainId,
            @McpToolParam(description = "Entity id") String entityId,
            @McpToolParam(required = false, description = "At most this many changes (1-1000, default 200)")
            Integer limit) {
        return results.call(() -> nodes.history(McpCaller.actor(ctx), brainId, entityId, limit));
    }

    @McpTool(name = "get_neighbors", description = "Entities connected to an entity (depth 1-2, at most 200"
            + " nodes), optionally only over some relationships." + NO_CYPHER,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public CallToolResult getNeighbors(McpTransportContext ctx, @McpToolParam(description = BRAIN) String brainId,
            @McpToolParam(description = "Entity id") String entityId,
            @McpToolParam(required = false, description = "1 (default) or 2") Integer depth,
            @McpToolParam(required = false, description = "Only these relationships, e.g. USES") List<String> predicates,
            @McpToolParam(required = false, description = "At most this many nodes (1-200, default 50)") Integer limit) {
        return results.call(() -> neighborhood.neighbors(McpCaller.actor(ctx), brainId, entityId, depth, predicates,
                limit));
    }

    // ------------------------------------------------------------------ claims

    @McpTool(name = "list_claims", description = "List facts (claims), filtered by subject, object, predicate"
            + " or status (ACTIVE, DISPUTED, SUPERSEDED, RETRACTED; absent = any).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public CallToolResult listClaims(McpTransportContext ctx, @McpToolParam(description = BRAIN) String brainId,
            @McpToolParam(required = false, description = "Subject entity id") String subjectId,
            @McpToolParam(required = false, description = "Object entity id") String objectId,
            @McpToolParam(required = false, description = "Predicate, e.g. USES") String predicate,
            @McpToolParam(required = false, description = "Claim status") String status,
            @McpToolParam(required = false, description = "next of the previous page") String cursor,
            @McpToolParam(required = false, description = "Page size (1-200, default 50)") Integer limit) {
        return results.call(() -> knowledge.listClaims(McpCaller.actor(ctx), brainId, subjectId, objectId, predicate,
                status, cursor, limit));
    }

    @McpTool(name = "get_claim", description = "One fact with its provenance: evidence, sources, supersession"
            + " links and conflicts.", annotations = @McpTool.McpAnnotations(readOnlyHint = true,
                    destructiveHint = false))
    public CallToolResult getClaim(McpTransportContext ctx, @McpToolParam(description = BRAIN) String brainId,
            @McpToolParam(description = "Claim id") String claimId) {
        return results.call(() -> knowledge.getClaim(McpCaller.actor(ctx), brainId, claimId));
    }

    @McpTool(name = "get_claim_history", description = "The chain of corrections of a fact, oldest first.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public CallToolResult getClaimHistory(McpTransportContext ctx, @McpToolParam(description = BRAIN) String brainId,
            @McpToolParam(description = "Claim id") String claimId) {
        return results.call(() -> knowledge.claimHistory(McpCaller.actor(ctx), brainId, claimId));
    }

    @McpTool(name = "assert_claim", description = "State a fact on behalf of the user (origin MANUAL, protected"
            + " from later LLM corrections): a relationship to objectId or an attribute value. A contradiction"
            + " becomes a conflict. Prefer remember for free text." + NO_CYPHER,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public CallToolResult assertClaim(McpTransportContext ctx, @McpToolParam(description = BRAIN) String brainId,
            @McpToolParam(description = "Subject entity id") String subjectId,
            @McpToolParam(description = "Predicate (see list_relationship_types)") String predicate,
            @McpToolParam(required = false, description = "Object entity id (relationships)") String objectId,
            @McpToolParam(required = false, description = "Attribute value (attributes)") String value,
            @McpToolParam(required = false, description = "POSITIVE (default) or NEGATIVE") String polarity,
            @McpToolParam(required = false, description = "The fact as one sentence") String statement,
            @McpToolParam(required = false, description = "Valid from: YYYY, YYYY-MM or YYYY-MM-DD") String validFrom,
            @McpToolParam(required = false, description = "Valid to: YYYY, YYYY-MM or YYYY-MM-DD") String validTo) {
        return results.call(() -> knowledge.assertClaim(McpCaller.actor(ctx), brainId, new NewClaim(subjectId,
                predicate, objectId, value, polarity, statement, validFrom, validTo)));
    }

    @McpTool(name = "supersede_claim", description = "Correct an ACTIVE fact on behalf of the user: it becomes"
            + " SUPERSEDED by a replacement with the same subject and predicate (history is kept).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public CallToolResult supersedeClaim(McpTransportContext ctx, @McpToolParam(description = BRAIN) String brainId,
            @McpToolParam(description = "Claim id to correct") String claimId,
            @McpToolParam(description = "Why it is corrected") String reason,
            @McpToolParam(required = false, description = "New object entity id (relationships)") String objectId,
            @McpToolParam(required = false, description = "New value (attributes)") String value,
            @McpToolParam(required = false, description = "POSITIVE (default) or NEGATIVE") String polarity,
            @McpToolParam(required = false, description = "The corrected fact as one sentence") String statement,
            @McpToolParam(required = false, description = "Valid from: YYYY, YYYY-MM or YYYY-MM-DD") String validFrom,
            @McpToolParam(required = false, description = "Valid to: YYYY, YYYY-MM or YYYY-MM-DD") String validTo) {
        return results.call(() -> knowledge.supersedeClaim(McpCaller.actor(ctx), brainId, claimId, new Supersession(
                reason, new Replacement(objectId, value, polarity, statement, validFrom, validTo))));
    }

    @McpTool(name = "retract_claim", description = "Retract an ACTIVE or DISPUTED fact on behalf of the user"
            + " (it is no longer true; history is kept).", annotations = @McpTool.McpAnnotations(
                    readOnlyHint = false, destructiveHint = false))
    public CallToolResult retractClaim(McpTransportContext ctx, @McpToolParam(description = BRAIN) String brainId,
            @McpToolParam(description = "Claim id") String claimId,
            @McpToolParam(description = "Why it is retracted") String reason) {
        return results.call(() -> knowledge.retractClaim(McpCaller.actor(ctx), brainId, claimId,
                new Retraction(reason)));
    }

    // ------------------------------------------------------------------ conflicts

    @McpTool(name = "list_conflicts", description = "Contradictions and suspected duplicates the server found"
            + " (status OPEN, RESOLVED or DISMISSED; kind optional).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public CallToolResult listConflicts(McpTransportContext ctx, @McpToolParam(description = BRAIN) String brainId,
            @McpToolParam(required = false, description = "OPEN, RESOLVED or DISMISSED") String status,
            @McpToolParam(required = false, description = "CONTRADICTION, DUPLICATE_CANDIDATE or PROTECTED_OVERRIDE")
            String kind,
            @McpToolParam(required = false, description = "next of the previous page") String cursor,
            @McpToolParam(required = false, description = "Page size (1-200, default 50)") Integer limit) {
        return results.call(() -> conflicts.list(McpCaller.actor(ctx), brainId, status, kind, cursor, limit));
    }

    @McpTool(name = "get_conflict", description = "A conflict with the claims and entities it involves.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public CallToolResult getConflict(McpTransportContext ctx, @McpToolParam(description = BRAIN) String brainId,
            @McpToolParam(description = "Conflict id") String conflictId) {
        return results.call(() -> conflicts.get(McpCaller.actor(ctx), brainId, conflictId));
    }

    @McpTool(name = "resolve_conflict", description = "Resolve a conflict on behalf of the user: KEEP_EXISTING,"
            + " ACCEPT_INCOMING, KEEP_BOTH (with validFrom/validTo), MERGE for duplicates (with survivorId) or"
            + " NOT_DUPLICATE.", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true))
    public CallToolResult resolveConflict(McpTransportContext ctx, @McpToolParam(description = BRAIN) String brainId,
            @McpToolParam(description = "Conflict id") String conflictId,
            @McpToolParam(description = "KEEP_EXISTING, ACCEPT_INCOMING, KEEP_BOTH, MERGE or NOT_DUPLICATE")
            String resolution,
            @McpToolParam(required = false, description = "A note for the record") String note,
            @McpToolParam(required = false, description = "MERGE: the entity that survives") String survivorId,
            @McpToolParam(required = false, description = "KEEP_BOTH: validity of the incoming claim") String validFrom,
            @McpToolParam(required = false, description = "KEEP_BOTH: end of the existing claim") String validTo) {
        return results.call(() -> conflicts.resolve(McpCaller.actor(ctx), brainId, conflictId, new Resolve(resolution,
                note, survivorId, validFrom, validTo)));
    }

    private static Instant instant(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw GraphSpindleException.validation(field + " must be an ISO-8601 instant, e.g. 2026-11-26T10:00:00Z");
        }
    }
}
