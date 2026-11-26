package io.graphspindle.api.rest;

import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.ingestion.IngestRequest;
import io.graphspindle.ingestion.IngestResult;
import io.graphspindle.ingestion.IngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM ingestion (ARCHITECTURE §3, §10). Synchronous: the response carries the result. Status codes:
 * 201 new ingestion (APPLIED or NO_CHANGES), 200 replay / duplicate / dry run, 502 FAILED with
 * {@code MALFORMED_MODEL_OUTPUT}, 503 FAILED with {@code LLM_UNAVAILABLE} (provider error or timeout;
 * when no provider is configured at all the answer is a 503 problem detail and nothing is recorded).
 */
@RestController
@RequestMapping("/api/v1/brains/{brainId}/ingestions")
@Tag(name = "Ingestions")
public class IngestionController {

    private final IngestionService ingestions;

    public IngestionController(IngestionService ingestions) {
        this.ingestions = ingestions;
    }

    @PostMapping
    @Operation(summary = "Remember unstructured content: the LLM extracts and reconciles it, the engine validates"
            + " and applies it (requestId makes retries idempotent)")
    public ResponseEntity<IngestResult> ingest(@PathVariable String brainId, @RequestBody IngestRequest request,
            @RequestParam(defaultValue = "false") boolean dryRun, @RequestParam(defaultValue = "false") boolean force) {
        IngestResult r = ingestions.ingest(KnowledgeController.actor(), brainId, request, dryRun, force);
        return ResponseEntity.status(status(r)).body(r);
    }

    @GetMapping("/{ingestionId}")
    @Operation(summary = "An ingestion's status and stored result")
    public IngestResult get(@PathVariable String brainId, @PathVariable String ingestionId) {
        return ingestions.get(KnowledgeController.actor(), brainId, ingestionId);
    }

    @GetMapping
    @Operation(summary = "Look up an ingestion by its client requestId")
    public IngestResult byRequestId(@PathVariable String brainId, @RequestParam(required = false) String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw GraphSpindleException.validation("requestId is required");
        }
        return ingestions.getByRequestId(KnowledgeController.actor(), brainId, requestId);
    }

    static int status(IngestResult r) {
        if ("FAILED".equals(r.status())) {
            String code = r.error() == null ? "INTERNAL" : r.error().code();
            return switch (code) {
                case "MALFORMED_MODEL_OUTPUT" -> 502;
                case "LLM_UNAVAILABLE" -> 503;
                default -> 500;
            };
        }
        if (r.replayed() || r.duplicateOf() != null || r.dryRun()) {
            return 200;
        }
        return 201;
    }
}
