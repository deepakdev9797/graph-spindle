package io.graphspindle.admin;

import io.graphspindle.brain.BrainService;
import io.graphspindle.common.Actor;
import io.graphspindle.common.Permission;
import io.graphspindle.common.BrainScope;
import io.graphspindle.graph.ProjectionVerifier;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Admin check that projections match claims (SCHEMA I3, §9.3): recomputes edges and {@code attr_*}
 * values from claims and reports drift. Read-only. {@code BRAIN_ADMIN} from M9.
 */
@Service
public class ProjectionCheckService {

    private final BrainService brains;
    private final ProjectionVerifier verifier;

    public ProjectionCheckService(BrainService brains, ProjectionVerifier verifier) {
        this.brains = brains;
        this.verifier = verifier;
    }

    public record Drift(String kind, String entityId, String claimId, String detail) {
    }

    public record ProjectionReport(String brainId, boolean consistent, List<Drift> drift) {
    }

    public ProjectionReport check(Actor actor, String brainId) {
        BrainScope scope = brains.requireActive(actor, brainId, Permission.ADMIN);
        List<Drift> drift = verifier.verify(scope).stream().map(d -> new Drift(d.kind(), d.entityId(), d.claimId(),
                d.detail())).toList();
        return new ProjectionReport(scope.brainId(), drift.isEmpty(), drift);
    }
}
