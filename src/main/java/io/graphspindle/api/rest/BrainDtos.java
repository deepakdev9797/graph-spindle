package io.graphspindle.api.rest;

import io.graphspindle.brain.Brain;
import io.graphspindle.brain.BrainConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** REST DTOs for brains. */
final class BrainDtos {

    private BrainDtos() {
    }

    @Schema(name = "CreateBrainRequest")
    record CreateBrainRequest(
            @Schema(description = "Immutable brain id", example = "dev", requiredMode = Schema.RequiredMode.REQUIRED)
            String id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Development brain") String name,
            String description,
            @Schema(description = "Display name of the self entity", example = "Me") String ownerName,
            BrainConfig config) {
    }

    @Schema(name = "UpdateBrainRequest", description = "Null fields are unchanged; config replaces the whole config")
    record UpdateBrainRequest(String name, String description, BrainConfig config) {
    }

    @Schema(name = "Brain")
    record BrainResponse(String id, String name, String description, String status, long configVersion,
            String selfEntityId, BrainConfig config, Instant createdAt, Instant updatedAt) {

        static BrainResponse of(Brain b) {
            return new BrainResponse(b.id(), b.name(), b.description(), b.status(), b.configVersion(),
                    b.selfEntityId(), b.config(), b.createdAt(), b.updatedAt());
        }
    }
}
