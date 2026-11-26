package io.graphspindle.api.rest;

import io.graphspindle.brain.VocabularyTypeService;
import io.graphspindle.brain.VocabularyTypeService.Versioned;
import io.graphspindle.vocabulary.NodeType;
import io.graphspindle.vocabulary.RelationshipType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Node-type and relationship-type CRUD per brain, plus the read-only core (YAML) view (ADR-025).
 * Per-brain responses carry the brain's {@code configVersion} as ETag; writes accept {@code If-Match}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Vocabulary")
public class VocabularyTypeController {

    private final VocabularyTypeService types;

    public VocabularyTypeController(VocabularyTypeService types) {
        this.types = types;
    }

    // ---- core profile (global, read-only) ---------------------------------------------------

    @GetMapping("/node-types")
    @Operation(summary = "Core node types from the YAML vocabulary (read-only, all brains)")
    public List<NodeType> coreNodeTypes() {
        return types.coreNodeTypes();
    }

    @GetMapping("/node-types/{type}")
    @Operation(summary = "One core node type from the YAML vocabulary")
    public NodeType coreNodeType(@PathVariable String type) {
        return types.coreNodeType(type);
    }

    @GetMapping("/relationship-types")
    @Operation(summary = "Core relationship types from the YAML vocabulary (read-only, all brains)")
    public List<RelationshipType> coreRelationshipTypes() {
        return types.coreRelationshipTypes();
    }

    @GetMapping("/relationship-types/{name}")
    @Operation(summary = "One core relationship type from the YAML vocabulary")
    public RelationshipType coreRelationshipType(@PathVariable String name) {
        return types.coreRelationshipType(name);
    }

    // ---- node types per brain ---------------------------------------------------------------

    @GetMapping("/brains/{brainId}/node-types")
    @Operation(summary = "The brain's node types: core (enabled or not) and custom, with properties")
    public ResponseEntity<List<NodeType>> nodeTypes(@PathVariable String brainId) {
        return ok(types.nodeTypes(brainId));
    }

    @GetMapping("/brains/{brainId}/node-types/{type}")
    @Operation(summary = "One node type of the brain")
    public ResponseEntity<NodeType> nodeType(@PathVariable String brainId, @PathVariable String type) {
        return ok(types.nodeType(brainId, type));
    }

    @PostMapping("/brains/{brainId}/node-types")
    @Operation(summary = "Create a custom node type (description required; similarity, caps and lock guardrails)")
    public ResponseEntity<NodeType> createNodeType(@PathVariable String brainId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody NodeType body) {
        Versioned<NodeType> v = types.createNodeType(brainId, ETags.parseIfMatch(ifMatch), body);
        return ResponseEntity.created(URI.create("/api/v1/brains/" + brainId + "/node-types/" + v.value().name()))
                .eTag(ETags.of(v.configVersion())).body(v.value());
    }

    @PutMapping("/brains/{brainId}/node-types/{type}")
    @Operation(summary = "Update a node type: custom types fully; core types only 'enabled' and extra properties. "
            + "Null fields keep their value; removing or narrowing a property in use is 409")
    public ResponseEntity<NodeType> updateNodeType(@PathVariable String brainId, @PathVariable String type,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody NodeType body) {
        return ok(types.updateNodeType(brainId, type, ETags.parseIfMatch(ifMatch), body));
    }

    @DeleteMapping("/brains/{brainId}/node-types/{type}")
    @Operation(summary = "Delete a custom node type (409 while entities use it; core types cannot be deleted)")
    public ResponseEntity<Void> deleteNodeType(@PathVariable String brainId, @PathVariable String type,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        long version = types.deleteNodeType(brainId, type, ETags.parseIfMatch(ifMatch));
        return ResponseEntity.noContent().eTag(ETags.of(version)).build();
    }

    // ---- relationship types per brain -------------------------------------------------------

    @GetMapping("/brains/{brainId}/relationship-types")
    @Operation(summary = "The brain's relationship types: core (enabled or not) and custom")
    public ResponseEntity<List<RelationshipType>> relationshipTypes(@PathVariable String brainId) {
        return ok(types.relationshipTypes(brainId));
    }

    @GetMapping("/brains/{brainId}/relationship-types/{name}")
    @Operation(summary = "One relationship type of the brain")
    public ResponseEntity<RelationshipType> relationshipType(@PathVariable String brainId,
            @PathVariable String name) {
        return ok(types.relationshipType(brainId, name));
    }

    @PostMapping("/brains/{brainId}/relationship-types")
    @Operation(summary = "Create a custom relationship type (description required; similarity and lock guardrails)")
    public ResponseEntity<RelationshipType> createRelationshipType(@PathVariable String brainId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody RelationshipType body) {
        Versioned<RelationshipType> v = types.createRelationshipType(brainId, ETags.parseIfMatch(ifMatch), body);
        return ResponseEntity.created(URI.create("/api/v1/brains/" + brainId + "/relationship-types/"
                + v.value().name())).eTag(ETags.of(v.configVersion())).body(v.value());
    }

    @PutMapping("/brains/{brainId}/relationship-types/{name}")
    @Operation(summary = "Update a relationship type: custom fully; core only 'enabled'. Narrowing in use is 409")
    public ResponseEntity<RelationshipType> updateRelationshipType(@PathVariable String brainId,
            @PathVariable String name,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody RelationshipType body) {
        return ok(types.updateRelationshipType(brainId, name, ETags.parseIfMatch(ifMatch), body));
    }

    @DeleteMapping("/brains/{brainId}/relationship-types/{name}")
    @Operation(summary = "Delete a custom relationship type (409 while claims use it)")
    public ResponseEntity<Void> deleteRelationshipType(@PathVariable String brainId, @PathVariable String name,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        long version = types.deleteRelationshipType(brainId, name, ETags.parseIfMatch(ifMatch));
        return ResponseEntity.noContent().eTag(ETags.of(version)).build();
    }

    private static <T> ResponseEntity<T> ok(Versioned<T> v) {
        return ResponseEntity.ok().eTag(ETags.of(v.configVersion())).body(v.value());
    }
}
