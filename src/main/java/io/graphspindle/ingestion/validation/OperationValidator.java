package io.graphspindle.ingestion.validation;

import static io.graphspindle.ingestion.validation.RejectionCode.DEPENDENCY_REJECTED;
import static io.graphspindle.ingestion.validation.RejectionCode.DUPLICATE_REF;
import static io.graphspindle.ingestion.validation.RejectionCode.EVIDENCE_NOT_FOUND;
import static io.graphspindle.ingestion.validation.RejectionCode.INVALID_VALUE;
import static io.graphspindle.ingestion.validation.RejectionCode.LIMIT_EXCEEDED;
import static io.graphspindle.ingestion.validation.RejectionCode.LOW_CONFIDENCE;
import static io.graphspindle.ingestion.validation.RejectionCode.NAME_INVALID;
import static io.graphspindle.ingestion.validation.RejectionCode.NOT_SLOT_CONFLICT;
import static io.graphspindle.ingestion.validation.RejectionCode.PROTECTED_TARGET;
import static io.graphspindle.ingestion.validation.RejectionCode.SECRET_DETECTED;
import static io.graphspindle.ingestion.validation.RejectionCode.SELF_LOOP;
import static io.graphspindle.ingestion.validation.RejectionCode.TYPE_INCOMPATIBLE_MATCH;
import static io.graphspindle.ingestion.validation.RejectionCode.TYPE_PAIR_NOT_ALLOWED;
import static io.graphspindle.ingestion.validation.RejectionCode.UNKNOWN_HANDLE;
import static io.graphspindle.ingestion.validation.RejectionCode.UNKNOWN_PREDICATE;
import static io.graphspindle.ingestion.validation.RejectionCode.UNKNOWN_TYPE;

import io.graphspindle.common.ClaimKind;
import io.graphspindle.common.Normalizer;
import io.graphspindle.common.Origin;
import io.graphspindle.common.PartialDate;
import io.graphspindle.common.Polarity;
import io.graphspindle.common.Untrusted;
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
import io.graphspindle.ingestion.validation.PlanCommand.AddAliasCommand;
import io.graphspindle.ingestion.validation.PlanCommand.AssertClaimCommand;
import io.graphspindle.ingestion.validation.PlanCommand.CreateEntityCommand;
import io.graphspindle.ingestion.validation.PlanCommand.FlagConflictCommand;
import io.graphspindle.ingestion.validation.PlanCommand.LinkSourceCommand;
import io.graphspindle.ingestion.validation.PlanCommand.ProposeMergeCommand;
import io.graphspindle.ingestion.validation.PlanCommand.ProtectedOverrideCommand;
import io.graphspindle.ingestion.validation.PlanCommand.RetractClaimCommand;
import io.graphspindle.ingestion.validation.PlanCommand.SupersedeClaimCommand;
import io.graphspindle.ingestion.validation.PlanCommand.UpdateEntityCommand;
import io.graphspindle.vocabulary.AttributePredicateDef;
import io.graphspindle.vocabulary.AttributeValues;
import io.graphspindle.vocabulary.BrainVocabulary;
import io.graphspindle.vocabulary.Cardinality;
import io.graphspindle.vocabulary.RelationPredicateDef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stage 4 of the ingestion pipeline (ARCHITECTURE §5): validates every proposed operation against
 * the brain vocabulary, the candidate set and the input, in pure Java with no I/O. Invalid
 * operations are dropped with a {@link ValidationIssue}; operations depending on a dropped
 * {@code ref} are dropped transitively; the rest become a {@link ValidatedPlan}.
 *
 * <p>Stateless and thread-safe; all per-run state lives in a private {@code Run}.
 */
public final class OperationValidator {

    public static final int MAX_NAME = 200;
    public static final int MAX_DESCRIPTION = 1000;
    public static final int MAX_REASON = 300;
    public static final int MAX_STATEMENT = 500;
    public static final int MAX_EVIDENCE = 300;
    public static final int MAX_CONFLICT_DESCRIPTION = 500;
    public static final int MAX_SUMMARY = 1000;

    private static final Pattern REF = Pattern.compile("^[a-z][a-z0-9_]{0,15}$");

    /** Names that only denote pronouns or stop words and must never become entities. */
    static final Set<String> STOP_NAMES = Set.of("i", "me", "my", "mine", "myself", "we", "us", "our", "ours",
            "ourselves", "you", "your", "yours", "yourself", "he", "him", "his", "she", "her", "hers", "it", "its",
            "they", "them", "their", "theirs", "this", "that", "these", "those", "someone", "somebody", "something",
            "everyone", "everybody", "everything", "anyone", "anything", "nobody", "nothing", "the", "a", "an", "and",
            "or", "of", "user", "speaker", "self", "here", "there");

    public ValidatedPlan validate(Proposal proposal, ValidationContext context) {
        return validate(ParsedProposal.of(proposal), context);
    }

    public ValidatedPlan validate(ParsedProposal parsed, ValidationContext context) {
        return new Run(parsed, context).execute();
    }

    /** One operation rejected. */
    private static final class Reject extends RuntimeException {

        private final RejectionCode code;

        Reject(RejectionCode code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    /** State of one validation run. */
    private static final class Run {

        private final ParsedProposal parsed;
        private final ValidationContext ctx;
        private final BrainVocabulary vocab;
        private final CandidateSet candidates;
        private final EvidenceChecker evidence;

        private final List<PlanCommand> commands = new ArrayList<>();
        private final List<ValidationIssue> rejected = new ArrayList<>();
        private final List<Adjustment> adjusted = new ArrayList<>();
        private final Map<String, ResolvedEntity> refs = new LinkedHashMap<>();
        private final Map<String, Integer> droppedRefs = new HashMap<>();
        private final Set<String> seenRefs = new HashSet<>();
        private final Map<String, ResolvedEntity.New> newByKey = new HashMap<>();
        private final Map<String, Integer> aliasesPerEntity = new HashMap<>();
        private final Map<String, Set<String>> aliasNormsPerEntity = new HashMap<>();
        private final Map<String, Integer> claimsPerSubject = new HashMap<>();
        private int newEntities;
        private int index;
        private String op;

        Run(ParsedProposal parsed, ValidationContext ctx) {
            this.parsed = parsed;
            this.ctx = ctx;
            this.vocab = ctx.vocabulary();
            this.candidates = ctx.candidates();
            this.evidence = new EvidenceChecker(ctx.content(), ctx.sourceSummaries());
        }

        ValidatedPlan execute() {
            rejected.addAll(parsed.rejected());
            List<MutationOperation> ops = parsed.proposal().operations();
            for (int i = 0; i < ops.size(); i++) {
                MutationOperation operation = ops.get(i);
                index = parsed.wireIndexes().get(i);
                op = operation.wireName();
                try {
                    if (index >= ctx.limits().maxOperations()) {
                        throw new Reject(LIMIT_EXCEEDED, "more than " + ctx.limits().maxOperations() + " operations");
                    }
                    apply(operation);
                } catch (Reject r) {
                    rejected.add(new ValidationIssue(index, op, r.code, r.getMessage()));
                    if (operation instanceof CreateEntity c && r.code != DUPLICATE_REF && REF.matcher(c.ref()).matches()
                            && !refs.containsKey(c.ref())) {
                        droppedRefs.putIfAbsent(c.ref(), index);
                    }
                }
            }
            rejected.sort(Comparator.comparingInt(ValidationIssue::opIndex));
            return new ValidatedPlan(summary(), commands, rejected, adjusted, refs);
        }

        private String summary() {
            String s = parsed.proposal().summary();
            if (s == null) {
                return null;
            }
            String out = s.strip();
            if (Normalizer.codePointLength(out) > MAX_SUMMARY) {
                out = Normalizer.truncate(out, MAX_SUMMARY);
                adjusted.add(new Adjustment(-1, null, Adjustment.Kind.SUMMARY_ADJUSTED, "summary truncated"));
            }
            if (ctx.secretScanner().containsSecret(out)) {
                out = ctx.secretScanner().redact(out);
                adjusted.add(new Adjustment(-1, null, Adjustment.Kind.SUMMARY_ADJUSTED, "secret redacted"));
            }
            return out;
        }

        private void apply(MutationOperation operation) {
            switch (operation) {
                case CreateEntity c -> createEntity(c);
                case UpdateEntity u -> updateEntity(u);
                case AddAlias a -> addAlias(a);
                case AssertClaim a -> assertClaim(a);
                case SupersedeClaim s -> supersede(s);
                case RetractClaim r -> retract(r);
                case FlagConflict f -> flagConflict(f);
                case ProposeMerge m -> proposeMerge(m);
                case LinkSource l -> linkSource(l);
            }
        }

        // ------------------------------------------------------------------ entities

        private void createEntity(CreateEntity c) {
            if (!REF.matcher(c.ref()).matches()) {
                throw new Reject(INVALID_VALUE, "ref must match " + REF.pattern());
            }
            if (!seenRefs.add(c.ref())) {
                throw new Reject(DUPLICATE_REF, "ref '" + c.ref() + "' is defined twice");
            }
            if (!vocab.isEnabledType(c.type())) {
                throw new Reject(UNKNOWN_TYPE, "entity type '" + Untrusted.preview(c.type()) + "' is not enabled");
            }
            String normalized = checkName("name", c.name());
            optionalText("description", c.description(), MAX_DESCRIPTION);
            if (c.aliases().size() > ctx.limits().maxAliasesPerEntity()) {
                throw new Reject(LIMIT_EXCEEDED, "at most " + ctx.limits().maxAliasesPerEntity() + " aliases");
            }
            List<AliasSpec> aliases = new ArrayList<>();
            Set<String> seen = new HashSet<>(Set.of(normalized));
            for (String alias : c.aliases()) {
                String norm = checkName("alias", alias);
                if (seen.add(norm)) {
                    aliases.add(new AliasSpec(alias.strip(), norm));
                } else {
                    adjust(Adjustment.Kind.ALIAS_DROPPED, "duplicate alias dropped");
                }
            }
            secrets(c.name(), c.description());
            c.aliases().forEach(this::secrets);

            String canonicalKey = Normalizer.canonicalKey(c.type(), normalized);
            Optional<EntityCandidate> existing = matchExisting(c.type(), normalized, canonicalKey);
            if (existing.isPresent()) {
                EntityCandidate e = existing.get();
                ResolvedEntity.Existing target = new ResolvedEntity.Existing(e.entityId(), e.type(), e.handle());
                checkWritable(target);
                List<AliasSpec> fresh = freshAliases(target, aliases, e.aliasNorms());
                countAliases(target, fresh.size());
                adjust(Adjustment.Kind.CREATE_BECAME_REFERENCE, "create_entity '" + c.ref()
                        + "' refers to existing candidate " + e.handle());
                if (c.description() != null && !c.description().isBlank()) {
                    adjust(Adjustment.Kind.DESCRIPTION_IGNORED, "description of an existing entity is not replaced"
                            + " by create_entity; use update_entity");
                }
                refs.put(c.ref(), target);
                fresh.forEach(a -> commands.add(new AddAliasCommand(index, target, a)));
                return;
            }
            ResolvedEntity.New earlier = newByKey.get(canonicalKey);
            if (earlier != null) {
                List<AliasSpec> fresh = freshAliases(earlier, aliases, Set.of());
                countAliases(earlier, fresh.size());
                adjust(Adjustment.Kind.CREATE_BECAME_REFERENCE, "create_entity '" + c.ref()
                        + "' repeats '" + earlier.ref() + "'");
                refs.put(c.ref(), earlier);
                fresh.forEach(a -> commands.add(new AddAliasCommand(index, earlier, a)));
                return;
            }
            if (newEntities >= ctx.limits().maxNewEntities()) {
                throw new Reject(LIMIT_EXCEEDED, "more than " + ctx.limits().maxNewEntities() + " new entities");
            }
            ResolvedEntity.New created = new ResolvedEntity.New(c.ref(), c.type(), canonicalKey);
            countAliases(created, aliases.size());
            aliasNormsPerEntity.computeIfAbsent(key(created), k -> new HashSet<>()).add(normalized);
            aliases.forEach(a -> aliasNormsPerEntity.get(key(created)).add(a.normalized()));
            newEntities++;
            newByKey.put(canonicalKey, created);
            refs.put(c.ref(), created);
            commands.add(new CreateEntityCommand(index, created, c.name().strip(), normalized, blankToNull(c.description()),
                    aliases));
        }

        /**
         * An existing candidate with the same canonical key, or else exactly one candidate with the
         * same normalized name and a compatible type (the existing type wins, SCHEMA §6.2).
         */
        private Optional<EntityCandidate> matchExisting(String type, String normalized, String canonicalKey) {
            Optional<EntityCandidate> exact = candidates.byCanonicalKey(canonicalKey);
            if (exact.isPresent()) {
                return exact;
            }
            List<EntityCandidate> compatible = candidates.byNormalizedName(normalized).stream()
                    .filter(e -> vocab.compatible(type, e.type())).toList();
            return compatible.size() == 1 ? Optional.of(compatible.get(0)) : Optional.empty();
        }

        private List<AliasSpec> freshAliases(ResolvedEntity target, List<AliasSpec> aliases, Set<String> known) {
            Set<String> have = aliasNormsPerEntity.computeIfAbsent(key(target), k -> new HashSet<>(known));
            List<AliasSpec> fresh = new ArrayList<>();
            for (AliasSpec a : aliases) {
                if (have.add(a.normalized())) {
                    fresh.add(a);
                } else {
                    adjust(Adjustment.Kind.ALIAS_DROPPED, "alias already known");
                }
            }
            return fresh;
        }

        private void countAliases(ResolvedEntity target, int n) {
            int total = aliasesPerEntity.getOrDefault(key(target), 0) + n;
            if (total > ctx.limits().maxAliasesPerEntity()) {
                throw new Reject(LIMIT_EXCEEDED, "more than " + ctx.limits().maxAliasesPerEntity()
                        + " aliases for one entity");
            }
            aliasesPerEntity.put(key(target), total);
        }

        private void updateEntity(UpdateEntity u) {
            ResolvedEntity target = resolve(u.target());
            checkWritable(target);
            requiredText("description", u.description(), MAX_DESCRIPTION);
            optionalText("reason", u.reason(), MAX_REASON);
            secrets(u.description(), u.reason());
            if (target instanceof ResolvedEntity.Existing e
                    && candidates.byEntityId(e.entityId()).map(EntityCandidate::descriptionLocked).orElse(false)) {
                commands.add(new ProtectedOverrideCommand(index, op, e.entityId(), null, null,
                        "update_entity on an entity whose description is locked by a manual edit"));
                throw new Reject(PROTECTED_TARGET, "description is locked; a PROTECTED_OVERRIDE conflict is raised");
            }
            commands.add(new UpdateEntityCommand(index, target, u.description().strip(), blankToNull(u.reason())));
        }

        private void addAlias(AddAlias a) {
            ResolvedEntity target = resolve(a.target());
            checkWritable(target);
            String norm = checkName("alias", a.alias());
            secrets(a.alias());
            Set<String> known = target instanceof ResolvedEntity.Existing e
                    ? candidates.byEntityId(e.entityId()).map(EntityCandidate::aliasNorms).orElse(Set.of())
                    : Set.of();
            List<AliasSpec> fresh = freshAliases(target, List.of(new AliasSpec(a.alias().strip(), norm)), known);
            countAliases(target, fresh.size());
            fresh.forEach(alias -> commands.add(new AddAliasCommand(index, target, alias)));
        }

        private void proposeMerge(ProposeMerge m) {
            ResolvedEntity a = resolve(m.a());
            ResolvedEntity b = resolve(m.b());
            if (a.sameAs(b)) {
                throw new Reject(SELF_LOOP, "an entity cannot be merged with itself");
            }
            if (!vocab.compatible(a.type(), b.type())) {
                throw new Reject(TYPE_INCOMPATIBLE_MATCH, a.type() + " and " + b.type() + " are not compatible");
            }
            requiredText("reason", m.reason(), MAX_REASON);
            secrets(m.reason());
            commands.add(new ProposeMergeCommand(index, a, b, m.reason().strip()));
        }

        private void linkSource(LinkSource l) {
            if (l.sourceIndex() < 0 || l.sourceIndex() >= ctx.sourceSummaries().size()) {
                throw new Reject(INVALID_VALUE, "source index " + l.sourceIndex() + " is not one of the "
                        + ctx.sourceSummaries().size() + " request sources");
            }
            ResolvedEntity target = resolve(l.target());
            commands.add(new LinkSourceCommand(index, l.sourceIndex(), target));
        }

        // ------------------------------------------------------------------ claims

        private void assertClaim(AssertClaim a) {
            ResolvedClaim claim = claim(a);
            claim = annotate(claim);
            countClaim(claim);
            commands.add(new AssertClaimCommand(index, claim));
        }

        private void supersede(SupersedeClaim s) {
            ClaimCandidate existing = claimHandle(s.claimHandle());
            requiredText("reason", s.reason(), MAX_REASON);
            secrets(s.reason());
            ResolvedClaim replacement = claim(s.replacement());
            boolean sameSubject = replacement.subject() instanceof ResolvedEntity.Existing e
                    && e.entityId().equals(existing.subjectEntityId());
            if (!sameSubject || !replacement.predicate().equals(existing.predicate())) {
                throw new Reject(NOT_SLOT_CONFLICT,
                        "replacement must have the same subject and predicate as claim " + s.claimHandle());
            }
            if (existing.origin() == Origin.MANUAL) {
                commands.add(new ProtectedOverrideCommand(index, op, null, existing.claimId(), replacement,
                        "supersede_claim on a MANUAL claim: " + s.reason().strip()));
                throw new Reject(PROTECTED_TARGET, "claim " + s.claimHandle()
                        + " is MANUAL; a PROTECTED_OVERRIDE conflict is raised");
            }
            countClaim(replacement);
            commands.add(new SupersedeClaimCommand(index, existing.claimId(), s.explicitCorrection(),
                    s.reason().strip(), replacement));
        }

        private void retract(RetractClaim r) {
            ClaimCandidate existing = claimHandle(r.claimHandle());
            requiredText("reason", r.reason(), MAX_REASON);
            requiredText("evidence", r.evidence(), MAX_EVIDENCE);
            secrets(r.reason(), r.evidence());
            checkEvidence(r.evidence());
            if (existing.origin() == Origin.MANUAL) {
                commands.add(new ProtectedOverrideCommand(index, op, null, existing.claimId(), null,
                        "retract_claim on a MANUAL claim: " + r.reason().strip()));
                throw new Reject(PROTECTED_TARGET, "claim " + r.claimHandle()
                        + " is MANUAL; a PROTECTED_OVERRIDE conflict is raised");
            }
            commands.add(new RetractClaimCommand(index, existing.claimId(), r.reason().strip(), r.evidence().strip()));
        }

        private void flagConflict(FlagConflict f) {
            ClaimCandidate existing = claimHandle(f.claimHandle());
            requiredText("description", f.description(), MAX_CONFLICT_DESCRIPTION);
            secrets(f.description());
            ResolvedClaim incoming = claim(f.incoming());
            countClaim(incoming);
            commands.add(new FlagConflictCommand(index, existing.claimId(), incoming, f.description().strip()));
        }

        /** Validates an assert_claim body (also used for replacements and incoming claims). */
        private ResolvedClaim claim(AssertClaim a) {
            ResolvedEntity subject = resolve(a.subject());
            checkWritable(subject);
            ResolvedEntity object = a.isRelation() ? resolve(a.object()) : null;

            Cardinality cardinality;
            ClaimKind kind;
            Double cap = null;
            AttributeValues.Checked value = null;
            AttributePredicateDef attribute = null;
            if (a.isRelation()) {
                RelationPredicateDef rel = vocab.relation(a.predicate()).orElseThrow(() -> new Reject(UNKNOWN_PREDICATE,
                        vocab.hasAttributeName(a.predicate())
                                ? "'" + a.predicate() + "' is an attribute predicate; use \"value\""
                                : "relation predicate '" + Untrusted.preview(a.predicate()) + "' is not enabled"));
                if (subject.sameAs(object)) {
                    throw new Reject(SELF_LOOP, "subject and object are the same entity");
                }
                if (!vocab.allowsRelation(rel.name(), subject.type(), object.type())) {
                    throw new Reject(TYPE_PAIR_NOT_ALLOWED, rel.name() + " does not allow " + subject.type() + " -> "
                            + object.type());
                }
                cardinality = rel.cardinality();
                kind = ClaimKind.RELATION;
                cap = rel.confidenceCap();
            } else {
                if (!vocab.hasAttributeName(a.predicate())) {
                    throw new Reject(UNKNOWN_PREDICATE, vocab.relation(a.predicate()).isPresent()
                            ? "'" + a.predicate() + "' is a relation predicate; use \"object\""
                            : "attribute predicate '" + Untrusted.preview(a.predicate()) + "' is not enabled");
                }
                attribute = vocab.attribute(a.predicate(), subject.type()).orElseThrow(() -> new Reject(
                        TYPE_PAIR_NOT_ALLOWED, "attribute '" + a.predicate() + "' does not apply to " + subject.type()));
                value = AttributeValues.check(attribute, a.value());
                if (value.engineOnly()) {
                    throw new Reject(INVALID_VALUE, value.error());
                }
                if (!value.ok()) {
                    throw new Reject(INVALID_VALUE, a.predicate() + ": " + value.error());
                }
                cardinality = attribute.cardinality();
                kind = ClaimKind.ATTRIBUTE;
            }

            String statement = requiredText("statement", a.statement(), MAX_STATEMENT);
            String quote = requiredText("evidence", a.evidence(), MAX_EVIDENCE);
            double confidence = a.confidence();
            if (!(confidence >= 0.0 && confidence <= 1.0)) {
                throw new Reject(INVALID_VALUE, "confidence must be between 0 and 1");
            }
            PartialDate from = partialDate("validFrom", a.validFrom());
            PartialDate to = partialDate("validTo", a.validTo());
            if (from != null && to != null && from.compareTo(to) > 0) {
                throw new Reject(INVALID_VALUE, "validFrom is after validTo");
            }
            List<Integer> sources = new ArrayList<>(new LinkedHashSet<>(a.sources()));
            for (int s : sources) {
                if (s < 0 || s >= ctx.sourceSummaries().size()) {
                    throw new Reject(INVALID_VALUE, "source index " + s + " is not one of the "
                            + ctx.sourceSummaries().size() + " request sources");
                }
            }
            secrets(statement, quote, a.value());
            checkEvidence(quote);
            if (confidence < ctx.policy().minConfidence()) {
                throw new Reject(LOW_CONFIDENCE, "confidence " + confidence + " is below "
                        + ctx.policy().minConfidence());
            }
            double max = Math.min(ctx.policy().maxLlmConfidence(), cap == null ? 1.0 : cap);
            if (confidence > max) {
                adjust(Adjustment.Kind.CONFIDENCE_CLAMPED, "confidence " + confidence + " clamped to " + max);
                confidence = max;
            }
            return new ResolvedClaim(subject, kind == ClaimKind.RELATION ? a.predicate() : attribute.name(), kind,
                    cardinality, object, attribute == null ? null : attribute.valueType(),
                    value == null ? null : value.value(), value == null ? null : value.valueNorm(), a.polarity(),
                    statement, quote, confidence, from, to, sources, null, null);
        }

        /** Marks contradictions with, or repetitions of, ACTIVE candidate claims (policy runs at apply time). */
        private ResolvedClaim annotate(ResolvedClaim claim) {
            if (!(claim.subject() instanceof ResolvedEntity.Existing subject)) {
                return claim;
            }
            String contradicts = null;
            String reinforces = null;
            for (ClaimCandidate c : candidates.claimsFor(subject.entityId(), claim.predicate())) {
                if (c.kind() != claim.kind()) {
                    continue;
                }
                boolean sameObject = claim.kind() == ClaimKind.RELATION
                        ? claim.object() instanceof ResolvedEntity.Existing o && o.entityId().equals(c.objectEntityId())
                        : claim.valueType() == c.valueType() && claim.valueNorm().equals(c.valueNorm());
                if (sameObject && c.polarity() == claim.polarity()) {
                    reinforces = c.claimId();
                } else if (sameObject) {
                    contradicts = contradicts == null ? c.claimId() : contradicts;
                } else if (claim.cardinality() == Cardinality.ONE && claim.polarity() == Polarity.POSITIVE
                        && c.polarity() == Polarity.POSITIVE) {
                    contradicts = contradicts == null ? c.claimId() : contradicts;
                }
            }
            if (reinforces != null) {
                adjust(Adjustment.Kind.REINFORCEMENT, "repeats an active claim; it will be reinforced");
                return claim.withAnnotations(null, reinforces);
            }
            if (contradicts != null) {
                adjust(Adjustment.Kind.CONTRADICTION_DETECTED, "contradicts an active claim; routed by the conflict policy");
                return claim.withAnnotations(contradicts, null);
            }
            return claim;
        }

        private void countClaim(ResolvedClaim claim) {
            String k = key(claim.subject());
            int n = claimsPerSubject.getOrDefault(k, 0) + 1;
            if (n > ctx.limits().maxClaimsPerSubject()) {
                throw new Reject(LIMIT_EXCEEDED, "more than " + ctx.limits().maxClaimsPerSubject()
                        + " claims about one subject");
            }
            claimsPerSubject.put(k, n);
        }

        // ------------------------------------------------------------------ helpers

        private ResolvedEntity resolve(EntityTarget target) {
            return switch (target) {
                case EntityTarget.Ref r -> {
                    ResolvedEntity e = refs.get(r.ref());
                    if (e != null) {
                        yield e;
                    }
                    Integer dropped = droppedRefs.get(r.ref());
                    if (dropped != null) {
                        throw new Reject(DEPENDENCY_REJECTED, "ref '" + r.ref() + "' was defined by rejected operation "
                                + dropped);
                    }
                    throw new Reject(UNKNOWN_HANDLE, "ref '" + Untrusted.preview(r.ref())
                            + "' is not defined by an earlier create_entity");
                }
                case EntityTarget.Handle h -> {
                    EntityCandidate c = candidates.entity(h.id()).orElseThrow(() -> new Reject(UNKNOWN_HANDLE,
                            "handle '" + Untrusted.preview(h.id()) + "' is not in this ingestion's candidate set"));
                    if (!c.mentionTypes().isEmpty()
                            && c.mentionTypes().stream().noneMatch(t -> vocab.compatible(t, c.type()))) {
                        throw new Reject(TYPE_INCOMPATIBLE_MATCH, "candidate " + h.id() + " is a " + c.type()
                                + " but was retrieved for a mention of type " + String.join("/", c.mentionTypes()));
                    }
                    yield new ResolvedEntity.Existing(c.entityId(), c.type(), c.handle());
                }
            };
        }

        /** Entities of a type disabled for the brain are read-only for ingestion (SCHEMA §11). */
        private void checkWritable(ResolvedEntity e) {
            if (!vocab.isEnabledType(e.type())) {
                throw new Reject(UNKNOWN_TYPE, "entity type " + e.type() + " is disabled; the entity is read-only");
            }
        }

        private ClaimCandidate claimHandle(String handle) {
            return candidates.claim(handle).orElseThrow(() -> new Reject(UNKNOWN_HANDLE,
                    "claim handle '" + Untrusted.preview(handle) + "' is not in this ingestion's candidate set"));
        }

        /** Length, '@', pronoun and emptiness checks for names and aliases; returns {@code norm(value)}. */
        private String checkName(String field, String value) {
            if (value == null || value.isBlank()) {
                throw new Reject(NAME_INVALID, field + " is empty");
            }
            if (Normalizer.codePointLength(value) > MAX_NAME) {
                throw new Reject(INVALID_VALUE, field + " must be at most " + MAX_NAME + " characters");
            }
            String norm = Normalizer.norm(value);
            if (norm.isEmpty()) {
                throw new Reject(NAME_INVALID, field + " is empty after normalization");
            }
            if (value.strip().startsWith("@") || norm.startsWith("@")) {
                throw new Reject(NAME_INVALID, field + " must not start with '@'");
            }
            if (STOP_NAMES.contains(norm)) {
                throw new Reject(NAME_INVALID, field + " is a pronoun or stop word");
            }
            return norm;
        }

        private String requiredText(String field, String value, int max) {
            if (value == null || value.isBlank()) {
                throw new Reject(INVALID_VALUE, field + " is required");
            }
            optionalText(field, value, max);
            return value.strip();
        }

        private void optionalText(String field, String value, int max) {
            if (value != null && Normalizer.codePointLength(value) > max) {
                throw new Reject(INVALID_VALUE, field + " must be at most " + max + " characters");
            }
        }

        private PartialDate partialDate(String field, String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return PartialDate.parse(raw.strip()).orElseThrow(() -> new Reject(INVALID_VALUE,
                    field + " must be YYYY, YYYY-MM or YYYY-MM-DD and a real date"));
        }

        private void secrets(String... values) {
            for (String v : values) {
                if (ctx.secretScanner().containsSecret(v)) {
                    throw new Reject(SECRET_DETECTED, "a field looks like a credential");
                }
            }
        }

        private void checkEvidence(String quote) {
            if (!evidence.isSupported(quote)) {
                throw new Reject(EVIDENCE_NOT_FOUND, "evidence is not a quote from the input");
            }
        }

        private void adjust(Adjustment.Kind kind, String message) {
            adjusted.add(new Adjustment(index, op, kind, message));
        }

        private static String key(ResolvedEntity e) {
            return switch (e) {
                case ResolvedEntity.Existing x -> "id:" + x.entityId();
                case ResolvedEntity.New n -> "key:" + n.canonicalKey();
            };
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s.strip();
        }
    }
}
