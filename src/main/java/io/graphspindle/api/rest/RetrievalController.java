package io.graphspindle.api.rest;

import io.graphspindle.retrieval.EntityContextService;
import io.graphspindle.retrieval.EntityContextService.EntityContext;
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
    private final EntityContextService context;
    private final NeighborhoodService neighborhood;

    public RetrievalController(SearchService search, EntityContextService context, NeighborhoodService neighborhood) {
        this.search = search;
        this.context = context;
        this.neighborhood = neighborhood;
    }

    @GetMapping("/search")
    @Operation(summary = "Search entities: exact alias first, then fulltext over names, claims and sources (limit ≤ 50)")
    public SearchResult search(@PathVariable String brainId, @RequestParam String q,
            @RequestParam(required = false) List<String> types, @RequestParam(required = false) Integer limit) {
        return search.search(KnowledgeController.actor(), brainId, q, types, limit);
    }

    @GetMapping("/entities/{entityId}")
    @Operation(summary = "Entity context: aliases, active claims with sources, disputed claims, conflicts")
    public EntityContext entity(@PathVariable String brainId, @PathVariable String entityId,
            @RequestParam(required = false) Integer maxClaims) {
        return context.context(KnowledgeController.actor(), brainId, entityId, maxClaims);
    }

    @GetMapping("/entities/{entityId}/neighbors")
    @Operation(summary = "Neighbourhood over projection edges (depth 1–2, at most 200 nodes)")
    public NeighborhoodResult neighbors(@PathVariable String brainId, @PathVariable String entityId,
            @RequestParam(required = false) Integer depth, @RequestParam(required = false) List<String> predicates,
            @RequestParam(required = false) Integer limit) {
        return neighborhood.neighbors(KnowledgeController.actor(), brainId, entityId, depth, predicates, limit);
    }
}
