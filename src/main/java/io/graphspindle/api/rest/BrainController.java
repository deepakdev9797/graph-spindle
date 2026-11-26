package io.graphspindle.api.rest;

import io.graphspindle.api.rest.BrainDtos.BrainResponse;
import io.graphspindle.api.rest.BrainDtos.CreateBrainRequest;
import io.graphspindle.api.rest.BrainDtos.UpdateBrainRequest;
import io.graphspindle.brain.Brain;
import io.graphspindle.brain.BrainService;
import io.graphspindle.brain.BrainUpdate;
import io.graphspindle.brain.NewBrain;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/brains")
@Tag(name = "Brains")
public class BrainController {

    private final BrainService brains;

    public BrainController(BrainService brains) {
        this.brains = brains;
    }

    @PostMapping
    @Operation(summary = "Create a brain (and its self entity)")
    public ResponseEntity<BrainResponse> create(@RequestBody CreateBrainRequest request) {
        Brain b = brains.create(new NewBrain(request.id(), request.name(), request.description(), request.ownerName(),
                request.config()));
        return ResponseEntity.created(URI.create("/api/v1/brains/" + b.id())).eTag(ETags.of(b.configVersion()))
                .body(BrainResponse.of(b));
    }

    @GetMapping
    @Operation(summary = "List brains")
    public List<BrainResponse> list() {
        return brains.list().stream().map(BrainResponse::of).toList();
    }

    @GetMapping("/{brainId}")
    @Operation(summary = "Get a brain")
    public ResponseEntity<BrainResponse> get(@PathVariable String brainId) {
        Brain b = brains.get(brainId);
        return ResponseEntity.ok().eTag(ETags.of(b.configVersion())).body(BrainResponse.of(b));
    }

    @PatchMapping("/{brainId}")
    @Operation(summary = "Update name, description or config (optimistic locking with If-Match: configVersion)")
    public ResponseEntity<BrainResponse> update(@PathVariable String brainId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateBrainRequest request) {
        Brain b = brains.update(brainId, ETags.parseIfMatch(ifMatch),
                new BrainUpdate(request.name(), request.description(), request.config()));
        return ResponseEntity.ok().eTag(ETags.of(b.configVersion())).body(BrainResponse.of(b));
    }

    @DeleteMapping("/{brainId}")
    @Operation(summary = "Delete a brain and all its knowledge; requires confirm=<brainId>")
    public ResponseEntity<Void> delete(@PathVariable String brainId,
            @RequestParam(name = "confirm", required = false) String confirm) {
        brains.delete(brainId, confirm);
        return ResponseEntity.noContent().build();
    }
}
