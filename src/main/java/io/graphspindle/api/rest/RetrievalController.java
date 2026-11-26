package io.graphspindle.api.rest;

import io.graphspindle.retrieval.EntityReadService;
import io.graphspindle.retrieval.EntityReadService.EntityRead;
import io.graphspindle.retrieval.NodeViewService;
import io.graphspindle.retrieval.NodeViewService.NodeHistory;
import io.swagger.v3.oas.annotations.Parameter;
import io.graphspindle.retrieval.NeighborhoodService;
import io.graphspindle.retrieval.NeighborhoodService.NeighborhoodResult;
import io.graphspindle.retrieval.SearchService;
import io.graphspindle.retrieval.SearchService.SearchResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Deterministic retrieval (ARCHITECTURE §9, §10): search, entity context and neighbourhood. */
@RestController
@RequestMapping("/api/v1/brains/{brainId}")
@Tag(name = "Retrieval")
public class RetrievalController {

    private final SearchService search;
    private final EntityReadService reads;
    private final NodeViewService nodes;
    private final NeighborhoodService neighborhood;

    public RetrievalController(SearchService search, EntityReadService reads, NodeViewService nodes,
            NeighborhoodService neighborhood) {
        this.search = search;
        this.reads = reads;
        this.nodes = nodes;
        this.neighborhood = neighborhood;
    }

    @GetMapping("/search")
    @Operation(summary = "Search entities: exact alias first, then fulltext over names, claims and sources (limit ≤ 50)")
    public SearchResult search(@PathVariable String brainId, @RequestParam String q,
            @RequestParam(required = false) List<String> types, @RequestParam(required = false) Integer limit) {
        return search.search(KnowledgeController.actor(), brainId, q, types, limit);
    }

    @GetMapping("/entities/{entityId}")
    @Operation(summary = "Plain entity view: node fields, properties and relationships. include=claims adds claims, "
            + "sources, disputes and the agent text; asOf (ISO date or date-time) shows what the brain believed then")
    public EntityRead entity(@PathVariable String brainId, @PathVariable String entityId,
            @RequestParam(required = false) Integer maxClaims,
            @Parameter(description = "comma-separated: claims") @RequestParam(required = false) List<String> include,
            @Parameter(description = "record-time point, e.g. 2026-11-20 (end of day UTC) or 2026-11-20T10:15:30Z")
            @RequestParam(required = false) String asOf) {
        return reads.read(KnowledgeController.actor(), brainId, entityId, NodeViewService.parseAsOf(asOf),
                EntityReadService.includesClaims(include), maxClaims);
    }

    @GetMapping("/entities/{entityId}/history")
    @Operation(summary = "Chronological property and relationship changes of the entity (record time), with "
            + "the claim, origin, ingestion and sources of each change")
    public NodeHistory history(@PathVariable String brainId, @PathVariable String entityId,
            @RequestParam(required = false) Integer limit) {
        return nodes.history(KnowledgeController.actor(), brainId, entityId, limit);
    }

    @GetMapping("/entities/{entityId}/neighbors")
    @Operation(summary = "Neighbourhood over projection edges (depth 1–2, at most 200 nodes)")
    public NeighborhoodResult neighbors(@PathVariable String brainId, @PathVariable String entityId,
            @RequestParam(required = false) Integer depth, @RequestParam(required = false) List<String> predicates,
            @RequestParam(required = false) Integer limit) {
        return neighborhood.neighbors(KnowledgeController.actor(), brainId, entityId, depth, predicates, limit);
    }
}
