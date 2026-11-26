package io.graphspindle.api.rest;

import io.graphspindle.common.Page;
import io.graphspindle.conflict.ConflictService;
import io.graphspindle.conflict.ConflictService.ConflictInfo;
import io.graphspindle.conflict.ConflictService.Resolve;
import io.graphspindle.conflict.ConflictService.ResolveResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/brains/{brainId}/conflicts")
@Tag(name = "Conflicts")
public class ConflictController {

    private final ConflictService conflicts;

    public ConflictController(ConflictService conflicts) {
        this.conflicts = conflicts;
    }

    @GetMapping
    @Operation(summary = "List conflicts (status OPEN|RESOLVED|DISMISSED, kind optional)")
    public Page<ConflictInfo> list(@PathVariable String brainId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return conflicts.list(KnowledgeController.actor(), brainId, status, kind, cursor, limit);
    }

    @GetMapping("/{conflictId}")
    @Operation(summary = "A conflict with the claims and entities it involves")
    public ConflictInfo get(@PathVariable String brainId, @PathVariable String conflictId) {
        return conflicts.get(KnowledgeController.actor(), brainId, conflictId);
    }

    @PostMapping("/{conflictId}/resolve")
    @Operation(summary = "Resolve: KEEP_EXISTING, ACCEPT_INCOMING, KEEP_BOTH, MERGE or NOT_DUPLICATE")
    public ResolveResult resolve(@PathVariable String brainId, @PathVariable String conflictId,
            @RequestBody Resolve request) {
        return conflicts.resolve(KnowledgeController.actor(), brainId, conflictId, request);
    }
}
