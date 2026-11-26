package io.graphspindle.ingestion.pipeline;

import io.graphspindle.common.Normalizer;
import io.graphspindle.ingestion.ops.CreateEntity;
import io.graphspindle.ingestion.validation.Adjustment;
import io.graphspindle.ingestion.validation.AliasSpec;
import io.graphspindle.ingestion.validation.CandidateSet;
import io.graphspindle.ingestion.validation.EntityCandidate;
import io.graphspindle.ingestion.validation.OperationValidator;
import io.graphspindle.ingestion.validation.PlanCommand;
import io.graphspindle.ingestion.validation.PlanCommand.CreateEntityCommand;
import io.graphspindle.ingestion.validation.PlanCommand.ProposeMergeCommand;
import io.graphspindle.ingestion.validation.ResolvedEntity;
import io.graphspindle.ingestion.validation.ValidatedPlan;
import io.graphspindle.resolution.NameMatch;
import io.graphspindle.vocabulary.BrainVocabulary;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SCHEMA §10.5: when the plan creates an entity whose name (or an alias) is at least
 * {@code duplicateSuspicionScore} similar ({@link NameMatch}) to an existing candidate of a
 * compatible type, and the model did not propose a merge for it, the engine adds a
 * {@code propose_merge} itself: a DUPLICATE_CANDIDATE conflict for a person to resolve. The engine
 * never merges on its own (ADR-030). The speaker created by the engine is not checked.
 */
public final class DuplicateSuspicion {

    static final int MAX_REASON = 300;

    private DuplicateSuspicion() {
    }

    public static ValidatedPlan addSuspectedDuplicates(ValidatedPlan plan, CandidateSet candidates,
            BrainVocabulary vocabulary, double threshold) {
        Set<String> proposed = new HashSet<>();
        for (ProposeMergeCommand m : plan.commands(ProposeMergeCommand.class)) {
            for (ResolvedEntity e : List.of(m.a(), m.b())) {
                if (e instanceof ResolvedEntity.New n) {
                    proposed.add(n.ref());
                }
            }
        }
        List<PlanCommand> commands = new ArrayList<>(plan.commands());
        List<Adjustment> adjusted = new ArrayList<>(plan.adjusted());
        boolean changed = false;
        for (CreateEntityCommand c : plan.commands(CreateEntityCommand.class)) {
            if (OperationValidator.SPEAKER_REF.equals(c.entity().ref()) || proposed.contains(c.entity().ref())) {
                continue;
            }
            List<String> names = new ArrayList<>();
            names.add(c.name());
            c.aliases().stream().map(AliasSpec::display).filter(d -> d != null).forEach(names::add);
            EntityCandidate best = null;
            double bestScore = 0;
            for (EntityCandidate cand : candidates.entities().values()) {
                if (OperationValidator.SPEAKER_HANDLE.equals(cand.handle())
                        || !vocabulary.compatible(c.entity().type(), cand.type())) {
                    continue;
                }
                List<String> known = new ArrayList<>(cand.aliasNorms());
                known.add(cand.name());
                double score = 0;
                for (String n : names) {
                    score = Math.max(score, NameMatch.best(n, known));
                }
                if (score > bestScore) {
                    best = cand;
                    bestScore = score;
                }
            }
            if (best == null || bestScore < threshold) {
                continue;
            }
            String reason = Normalizer.truncate(String.format(Locale.ROOT,
                    "engine: new %s \"%s\" looks like existing %s \"%s\" (name similarity %.2f)", c.entity().type(),
                    c.name(), best.type(), best.name(), bestScore), MAX_REASON);
            commands.add(new ProposeMergeCommand(c.opIndex(), c.entity(),
                    new ResolvedEntity.Existing(best.entityId(), best.type(), best.handle()), reason));
            adjusted.add(new Adjustment(c.opIndex(), CreateEntity.OP, Adjustment.Kind.DUPLICATE_SUSPECTED, reason));
            changed = true;
        }
        return changed ? new ValidatedPlan(plan.summary(), commands, plan.rejected(), adjusted, plan.refs()) : plan;
    }
}
