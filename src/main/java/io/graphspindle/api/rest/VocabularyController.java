package io.graphspindle.api.rest;

import io.graphspindle.brain.BrainService;
import io.graphspindle.brain.BrainVocabularyView;
import io.graphspindle.vocabulary.AttributePredicateDef;
import io.graphspindle.vocabulary.EntityTypeDef;
import io.graphspindle.vocabulary.RelationPredicateDef;
import io.graphspindle.vocabulary.VocabularyConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/brains/{brainId}/vocabulary")
@Tag(name = "Vocabulary")
public class VocabularyController {

    private final BrainService brains;

    public VocabularyController(BrainService brains) {
        this.brains = brains;
    }

    @GetMapping
    @Operation(summary = "The brain's resolved vocabulary and its configuration")
    public ResponseEntity<VocabularyResponse> get(@PathVariable String brainId) {
        return respond(brains.vocabularyView(brainId));
    }

    @PutMapping
    @Operation(summary = "Replace the brain's vocabulary configuration (validated, bumps configVersion)")
    public ResponseEntity<VocabularyResponse> put(@PathVariable String brainId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody VocabularyConfig config) {
        return respond(brains.updateVocabulary(brainId, ETags.parseIfMatch(ifMatch), config));
    }

    private static ResponseEntity<VocabularyResponse> respond(BrainVocabularyView view) {
        var v = view.vocabulary();
        var body = new VocabularyResponse(view.brain().id(), view.brain().configVersion(), v.profile(),
                view.brain().config().vocabulary(), v.entityTypes(), v.relationPredicates(), v.attributePredicates());
        return ResponseEntity.ok().eTag(ETags.of(view.brain().configVersion())).body(body);
    }

    @Schema(name = "Vocabulary")
    record VocabularyResponse(String brainId, long configVersion, String profile, VocabularyConfig config,
            List<EntityTypeDef> entityTypes, List<RelationPredicateDef> relationPredicates,
            List<AttributePredicateDef> attributePredicates) {
    }
}
