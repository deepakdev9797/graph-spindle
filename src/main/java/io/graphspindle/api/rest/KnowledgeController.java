package io.graphspindle.api.rest;

import io.graphspindle.common.Actor;
import io.graphspindle.common.Page;
import io.graphspindle.knowledge.KnowledgeModel.AliasInfo;
import io.graphspindle.knowledge.KnowledgeModel.ClaimDetail;
import io.graphspindle.knowledge.KnowledgeModel.ClaimInfo;
import io.graphspindle.knowledge.KnowledgeModel.ClaimWrite;
import io.graphspindle.knowledge.KnowledgeModel.EntityChanges;
import io.graphspindle.knowledge.KnowledgeModel.EntityInfo;
import io.graphspindle.knowledge.KnowledgeModel.EntityWrite;
import io.graphspindle.knowledge.KnowledgeModel.HistoryEntry;
import io.graphspindle.knowledge.KnowledgeModel.MergeRequest;
import io.graphspindle.knowledge.KnowledgeModel.MergeResult;
import io.graphspindle.knowledge.KnowledgeModel.NewAlias;
import io.graphspindle.knowledge.KnowledgeModel.NewClaim;
import io.graphspindle.knowledge.KnowledgeModel.NewEntity;
import io.graphspindle.knowledge.KnowledgeModel.Retraction;
import io.graphspindle.knowledge.KnowledgeModel.SourceDetail;
import io.graphspindle.knowledge.KnowledgeModel.SourceInfo;
import io.graphspindle.knowledge.KnowledgeModel.Supersession;
import io.graphspindle.knowledge.KnowledgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Manual knowledge management (ARCHITECTURE §10): one service call per endpoint, no logic. */
@RestController
@RequestMapping("/api/v1/brains/{brainId}")
@Tag(name = "Knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledge;

    public KnowledgeController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    /** Until authentication exists (M9) every request acts as the local admin. */
    static Actor actor() {
        return Actor.LOCAL_ADMIN;
    }

    @GetMapping("/entities")
    @Operation(summary = "List entities (keyset pagination by cursor)")
    public Page<EntityInfo> listEntities(@PathVariable String brainId, @RequestParam(required = false) String type,
            @RequestParam(required = false) String status, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return knowledge.listEntities(actor(), brainId, type, status, cursor, limit);
    }

    @PostMapping("/entities")
    @Operation(summary = "Create a MANUAL entity (201), or return the existing one with the same key (200)")
    public ResponseEntity<EntityWrite> createEntity(@PathVariable String brainId, @RequestBody NewEntity request) {
        EntityWrite w = knowledge.createEntity(actor(), brainId, request);
        return w.created() ? ResponseEntity.created(URI.create("/api/v1/brains/" + brainId + "/entities/"
                + w.entity().id())).body(w) : ResponseEntity.ok(w);
    }

    @PatchMapping("/entities/{entityId}")
    @Operation(summary = "Edit name, description (locks it against the LLM) or the description lock")
    public EntityInfo updateEntity(@PathVariable String brainId, @PathVariable String entityId,
            @RequestBody EntityChanges changes) {
        return knowledge.updateEntity(actor(), brainId, entityId, changes);
    }

    @DeleteMapping("/entities/{entityId}")
    @Operation(summary = "Archive an entity (soft delete)")
    public EntityInfo archiveEntity(@PathVariable String brainId, @PathVariable String entityId) {
        return knowledge.archiveEntity(actor(), brainId, entityId);
    }

    @GetMapping("/entities/{entityId}/aliases")
    @Operation(summary = "Aliases of an entity")
    public List<AliasInfo> aliases(@PathVariable String brainId, @PathVariable String entityId) {
        return knowledge.aliases(actor(), brainId, entityId);
    }

    @PostMapping("/entities/{entityId}/aliases")
    @Operation(summary = "Add an alias (MANUAL)")
    public EntityWrite addAlias(@PathVariable String brainId, @PathVariable String entityId,
            @RequestBody NewAlias request) {
        return knowledge.addAlias(actor(), brainId, entityId, request);
    }

    @PostMapping("/entities/merge")
    @Operation(summary = "Merge loserId into survivorId (claims re-pointed, aliases moved, loser redirects)")
    public MergeResult merge(@PathVariable String brainId, @RequestBody MergeRequest request) {
        return knowledge.mergeEntities(actor(), brainId, request);
    }

    @GetMapping("/claims")
    @Operation(summary = "List claims (filters optional; status absent = any status)")
    public Page<ClaimInfo> listClaims(@PathVariable String brainId, @RequestParam(required = false) String subjectId,
            @RequestParam(required = false) String objectId, @RequestParam(required = false) String predicate,
            @RequestParam(required = false) String status, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return knowledge.listClaims(actor(), brainId, subjectId, objectId, predicate, status, cursor, limit);
    }

    @PostMapping("/claims")
    @Operation(summary = "Assert a MANUAL claim (201 when a new claim was created)")
    public ResponseEntity<ClaimWrite> assertClaim(@PathVariable String brainId, @RequestBody NewClaim request) {
        ClaimWrite w = knowledge.assertClaim(actor(), brainId, request);
        return "CREATED".equals(w.outcome()) || "DISPUTED".equals(w.outcome())
                ? ResponseEntity.created(URI.create("/api/v1/brains/" + brainId + "/claims/" + w.claim().id())).body(w)
                : ResponseEntity.ok(w);
    }

    @GetMapping("/claims/{claimId}")
    @Operation(summary = "A claim with its provenance (evidence, sources, supersession links, conflicts)")
    public ClaimDetail getClaim(@PathVariable String brainId, @PathVariable String claimId) {
        return knowledge.getClaim(actor(), brainId, claimId);
    }

    @GetMapping("/claims/{claimId}/history")
    @Operation(summary = "The SUPERSEDES chain of a claim, oldest first")
    public List<HistoryEntry> history(@PathVariable String brainId, @PathVariable String claimId) {
        return knowledge.claimHistory(actor(), brainId, claimId);
    }

    @PostMapping("/claims/{claimId}/supersede")
    @Operation(summary = "Correct an ACTIVE claim: it is SUPERSEDED by the replacement")
    public ClaimWrite supersede(@PathVariable String brainId, @PathVariable String claimId,
            @RequestBody Supersession request) {
        return knowledge.supersedeClaim(actor(), brainId, claimId, request);
    }

    @PostMapping("/claims/{claimId}/retract")
    @Operation(summary = "Retract an ACTIVE or DISPUTED claim (history kept)")
    public ClaimWrite retract(@PathVariable String brainId, @PathVariable String claimId,
            @RequestBody Retraction request) {
        return knowledge.retractClaim(actor(), brainId, claimId, request);
    }

    @GetMapping("/sources")
    @Operation(summary = "List source references (stored, never fetched)")
    public Page<SourceInfo> listSources(@PathVariable String brainId, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return knowledge.listSources(actor(), brainId, cursor, limit);
    }

    @GetMapping("/sources/{sourceId}")
    @Operation(summary = "A source reference with the entities it describes and the claims citing it")
    public SourceDetail getSource(@PathVariable String brainId, @PathVariable String sourceId) {
        return knowledge.getSource(actor(), brainId, sourceId);
    }
}
