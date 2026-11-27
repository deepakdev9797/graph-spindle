package io.graphspindle.api.rest;

import io.graphspindle.security.ApiKeyService;
import io.graphspindle.security.ApiKeys.ApiKeyInfo;
import io.graphspindle.security.ApiKeys.CreatedApiKey;
import io.graphspindle.security.ApiKeys.NewApiKey;
import io.graphspindle.security.ApiKeys.NewBrainApiKey;
import io.graphspindle.security.CurrentActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * API key management (ADR-033), REST only. Global endpoints need the admin key; per-brain endpoints
 * need {@code ADMIN} on that brain. The full key is returned once, by the create call.
 */
@RestController
@Tag(name = "API keys")
public class ApiKeyController {

    private final ApiKeyService keys;

    public ApiKeyController(ApiKeyService keys) {
        this.keys = keys;
    }

    @PostMapping("/api/v1/api-keys")
    @Operation(summary = "Create a key with grants on one or more brains (admin). The key is shown only once.")
    public ResponseEntity<CreatedApiKey> create(@RequestBody NewApiKey request) {
        return created(keys.create(CurrentActor.require(), request), "/api/v1/api-keys/");
    }

    @GetMapping("/api/v1/api-keys")
    @Operation(summary = "List every key (admin); never secrets")
    public List<ApiKeyInfo> list() {
        return keys.list(CurrentActor.require());
    }

    @DeleteMapping("/api/v1/api-keys/{keyId}")
    @Operation(summary = "Revoke a key on every brain (admin)")
    public ApiKeyInfo revoke(@PathVariable String keyId) {
        return keys.revoke(CurrentActor.require(), keyId);
    }

    @PostMapping("/api/v1/brains/{brainId}/api-keys")
    @Operation(summary = "Create a key for this brain (brain ADMIN). The key is shown only once.")
    public ResponseEntity<CreatedApiKey> createForBrain(@PathVariable String brainId,
            @RequestBody NewBrainApiKey request) {
        return created(keys.createForBrain(CurrentActor.require(), brainId, request),
                "/api/v1/brains/" + brainId + "/api-keys/");
    }

    @GetMapping("/api/v1/brains/{brainId}/api-keys")
    @Operation(summary = "Keys with access to this brain (brain ADMIN); only this brain's grant is shown")
    public List<ApiKeyInfo> listForBrain(@PathVariable String brainId) {
        return keys.listForBrain(CurrentActor.require(), brainId);
    }

    @GetMapping("/api/v1/brains/{brainId}/api-keys/{keyId}")
    @Operation(summary = "One key with access to this brain (brain ADMIN)")
    public ApiKeyInfo getForBrain(@PathVariable String brainId, @PathVariable String keyId) {
        return keys.getForBrain(CurrentActor.require(), brainId, keyId);
    }

    @DeleteMapping("/api/v1/brains/{brainId}/api-keys/{keyId}")
    @Operation(summary = "Remove the key's access to this brain (brain ADMIN); a key left without brains is revoked")
    public ApiKeyInfo revokeForBrain(@PathVariable String brainId, @PathVariable String keyId) {
        return keys.revokeForBrain(CurrentActor.require(), brainId, keyId);
    }

    private static ResponseEntity<CreatedApiKey> created(CreatedApiKey k, String base) {
        return ResponseEntity.created(URI.create(base + k.key().id())).cacheControl(CacheControl.noStore()).body(k);
    }
}
