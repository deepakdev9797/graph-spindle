package io.graphspindle.api.rest;

import io.graphspindle.security.CurrentActor;
import io.graphspindle.admin.ProjectionCheckService;
import io.graphspindle.admin.ProjectionCheckService.ProjectionReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin")
public class AdminController {

    private final ProjectionCheckService projections;

    public AdminController(ProjectionCheckService projections) {
        this.projections = projections;
    }

    @GetMapping("/brains/{brainId}/projection-check")
    @Operation(summary = "Recompute projections from claims and report drift (read-only)")
    public ProjectionReport projectionCheck(@PathVariable String brainId) {
        return projections.check(CurrentActor.require(), brainId);
    }
}
