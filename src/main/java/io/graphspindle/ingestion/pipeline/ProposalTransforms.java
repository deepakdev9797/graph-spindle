package io.graphspindle.ingestion.pipeline;

import io.graphspindle.common.Normalizer;
import io.graphspindle.ingestion.ops.AddAlias;
import io.graphspindle.ingestion.ops.AssertClaim;
import io.graphspindle.ingestion.ops.CreateEntity;
import io.graphspindle.ingestion.ops.EntityTarget;
import io.graphspindle.ingestion.ops.FlagConflict;
import io.graphspindle.ingestion.ops.LinkSource;
import io.graphspindle.ingestion.ops.MutationOperation;
import io.graphspindle.ingestion.ops.ParsedProposal;
import io.graphspindle.ingestion.ops.Proposal;
import io.graphspindle.ingestion.ops.ProposeMerge;
import io.graphspindle.ingestion.ops.RetractClaim;
import io.graphspindle.ingestion.ops.SupersedeClaim;
import io.graphspindle.ingestion.ops.UpdateEntity;
import io.graphspindle.ingestion.validation.Adjustment;
import io.graphspindle.ingestion.validation.OperationValidator;
import java.util.ArrayList;
import java.util.List;

/**
 * Engine-side rewrites of a parsed proposal before validation (ADR-028). They only ever make a
 * proposal more conservative or bind the engine's own speaker identity; they never add model facts.
 */
public final class ProposalTransforms {

    static final int MAX_FLAG_DESCRIPTION = 500;

    private ProposalTransforms() {
    }

    /**
     * Every {@code supersede_claim} becomes a {@code flag_conflict} (incoming claim DISPUTED plus a
     * CONTRADICTION conflict against the targeted claim) unless the input itself contains correction
     * language and the model marked it as an explicit correction. The model's {@code explicitCorrection}
     * flag alone is never trusted.
     */
    public static ParsedProposal downgradeUnsupportedCorrections(ParsedProposal parsed, CorrectionCues cues,
            String redactedContent) {
        boolean cue = cues.present(redactedContent);
        List<MutationOperation> ops = new ArrayList<>();
        List<Adjustment> adjustments = new ArrayList<>(parsed.adjustments());
        boolean changed = false;
        for (int i = 0; i < parsed.proposal().operations().size(); i++) {
            MutationOperation op = parsed.proposal().operations().get(i);
            if (op instanceof SupersedeClaim s && !(cue && s.explicitCorrection())) {
                String why = cue ? "the model did not mark it as an explicit correction"
                        : "the input contains no correction language";
                ops.add(new FlagConflict(s.claimHandle(), s.replacement(), Normalizer.truncate(
                        "Possible contradiction, not applied as a correction (" + why + "): " + s.reason().strip(),
                        MAX_FLAG_DESCRIPTION)));
                adjustments.add(new Adjustment(parsed.wireIndexes().get(i), SupersedeClaim.OP,
                        Adjustment.Kind.CORRECTION_DOWNGRADED, "supersede_claim of " + s.claimHandle()
                                + " recorded as a disputed claim with a conflict: " + why));
                changed = true;
            } else {
                ops.add(op);
            }
        }
        if (!changed) {
            return parsed;
        }
        return new ParsedProposal(new Proposal(parsed.proposal().summary(), ops), parsed.wireIndexes(),
                parsed.rejected(), parsed.unknownFields(), adjustments, parsed.droppedRefs());
    }

    /**
     * The request names a speaker that does not exist in the brain yet: the engine prepends a
     * {@code create_entity} for it (wire index -1, ref {@value OperationValidator#SPEAKER_REF}) and
     * points every use of the handle {@code P0} at that ref. Nothing is created unless the plan is applied.
     */
    public static ParsedProposal createSpeaker(ParsedProposal parsed, String type, String name) {
        List<MutationOperation> ops = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        ops.add(new CreateEntity(OperationValidator.SPEAKER_REF, type, name, null, List.of()));
        indexes.add(-1);
        for (int i = 0; i < parsed.proposal().operations().size(); i++) {
            ops.add(rebind(parsed.proposal().operations().get(i)));
            indexes.add(parsed.wireIndexes().get(i));
        }
        List<Adjustment> adjustments = new ArrayList<>(parsed.adjustments());
        adjustments.add(new Adjustment(-1, CreateEntity.OP, Adjustment.Kind.SPEAKER_CREATED,
                "the speaker " + type + " is new in this brain and is created with this ingestion"));
        return new ParsedProposal(new Proposal(parsed.proposal().summary(), ops), indexes, parsed.rejected(),
                parsed.unknownFields(), adjustments, parsed.droppedRefs());
    }

    private static MutationOperation rebind(MutationOperation op) {
        return switch (op) {
            case CreateEntity c -> c;
            case UpdateEntity u -> new UpdateEntity(t(u.target()), u.description(), u.reason());
            case AddAlias a -> new AddAlias(t(a.target()), a.alias());
            case AssertClaim a -> claim(a);
            case SupersedeClaim s -> new SupersedeClaim(s.claimHandle(), s.explicitCorrection(), s.reason(),
                    claim(s.replacement()));
            case RetractClaim r -> r;
            case FlagConflict f -> new FlagConflict(f.claimHandle(), claim(f.incoming()), f.description());
            case ProposeMerge m -> new ProposeMerge(t(m.a()), t(m.b()), m.reason());
            case LinkSource l -> new LinkSource(l.sourceIndex(), t(l.target()));
        };
    }

    private static AssertClaim claim(AssertClaim a) {
        return new AssertClaim(t(a.subject()), a.predicate(), a.object() == null ? null : t(a.object()), a.value(),
                a.polarity(), a.statement(), a.evidence(), a.confidence(), a.validFrom(), a.validTo(), a.sources());
    }

    private static EntityTarget t(EntityTarget target) {
        return target instanceof EntityTarget.Handle h && OperationValidator.SPEAKER_HANDLE.equals(h.id())
                ? EntityTarget.ref(OperationValidator.SPEAKER_REF) : target;
    }
}
