package io.graphspindle.ingestion.validation;

import java.util.List;

/**
 * A validated, fully resolved command of a {@link ValidatedPlan}. Each carries the index of the
 * model operation it came from.
 */
public sealed interface PlanCommand {

    int opIndex();

    record CreateEntityCommand(int opIndex, ResolvedEntity.New entity, String name, String normalizedName,
            String description, List<AliasSpec> aliases) implements PlanCommand {

        public CreateEntityCommand {
            aliases = List.copyOf(aliases);
        }
    }

    record UpdateEntityCommand(int opIndex, ResolvedEntity target, String description, String reason)
            implements PlanCommand {
    }

    record AddAliasCommand(int opIndex, ResolvedEntity target, AliasSpec alias) implements PlanCommand {
    }

    record AssertClaimCommand(int opIndex, ResolvedClaim claim) implements PlanCommand {
    }

    record SupersedeClaimCommand(int opIndex, String claimId, boolean explicitCorrection, String reason,
            ResolvedClaim replacement) implements PlanCommand {
    }

    record RetractClaimCommand(int opIndex, String claimId, String reason, String evidence) implements PlanCommand {
    }

    /** The incoming claim is stored DISPUTED and a CONTRADICTION conflict is opened. */
    record FlagConflictCommand(int opIndex, String claimId, ResolvedClaim incoming, String description)
            implements PlanCommand {
    }

    /** Creates a DUPLICATE_CANDIDATE conflict; never merges. */
    record ProposeMergeCommand(int opIndex, ResolvedEntity a, ResolvedEntity b, String reason) implements PlanCommand {
    }

    record LinkSourceCommand(int opIndex, int sourceIndex, ResolvedEntity target) implements PlanCommand {
    }

    /**
     * A {@code PROTECTED_TARGET} rejection turned into a PROTECTED_OVERRIDE conflict: the protected
     * claim or entity is untouched.
     *
     * @param attemptedOp the wire op that was refused
     * @param targetEntityId the locked entity (update_entity), else null
     * @param targetClaimId the MANUAL claim (supersede/retract), else null
     * @param incoming the replacement claim for supersede, else null (stored DISPUTED)
     */
    record ProtectedOverrideCommand(int opIndex, String attemptedOp, String targetEntityId, String targetClaimId,
            ResolvedClaim incoming, String description) implements PlanCommand {
    }
}
