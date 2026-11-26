package io.graphspindle.ingestion.validation;

import static io.graphspindle.ingestion.validation.Fixtures.CORE;
import static io.graphspindle.ingestion.validation.Fixtures.VALIDATOR;
import static io.graphspindle.ingestion.validation.Fixtures.attribute;
import static io.graphspindle.ingestion.validation.Fixtures.entity;
import static io.graphspindle.ingestion.validation.Fixtures.relation;
import static io.graphspindle.ingestion.validation.Fixtures.self;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.graphspindle.common.Limits;
import io.graphspindle.common.Normalizer;
import io.graphspindle.common.Origin;
import io.graphspindle.ingestion.ops.AssertClaim;
import io.graphspindle.ingestion.ops.CreateEntity;
import io.graphspindle.ingestion.ops.EntityTarget;
import io.graphspindle.ingestion.ops.MutationOperation;
import io.graphspindle.ingestion.ops.ParsedProposal;
import io.graphspindle.ingestion.ops.ProposalParser;
import io.graphspindle.ingestion.ops.Proposal;
import io.graphspindle.ingestion.ops.ProposeMerge;
import io.graphspindle.ingestion.ops.RetractClaim;
import io.graphspindle.ingestion.validation.PlanCommand.CreateEntityCommand;
import io.graphspindle.vocabulary.AttributeValues;
import io.graphspindle.vocabulary.BrainVocabulary;
import io.graphspindle.vocabulary.Cardinality;
import io.graphspindle.vocabulary.ValueType;
import io.graphspindle.vocabulary.VocabularyConfig;
import io.graphspindle.vocabulary.VocabularyConfig.CustomAttributePredicate;
import io.graphspindle.vocabulary.VocabularyConfig.CustomEntityType;
import io.graphspindle.vocabulary.VocabularyConfigValidator;
import io.graphspindle.vocabulary.VocabularyRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class M3HardeningTest {

    static final String CONTENT = "GraphSpindle uses Neo4j and the team likes it. Alice is here.";

    static ValidatedPlan validate(ValidationContext ctx, MutationOperation... ops) {
        return VALIDATOR.validate(new Proposal("s", List.of(ops)), ctx);
    }

    static ValidationContext ctx(BrainVocabulary v, CandidateSet c) {
        return ValidationContext.of("dev", v, c, CONTENT, List.of());
    }

    // F1: NUMBER attribute value with huge exponent -> toPlainString builds a ~1e9 char string (OOM / CPU DoS)
    @Test
    void f1_numberWithHugeExponentIsRejectedQuickly() {
        BrainVocabulary v = new VocabularyRegistry().forBrain(new VocabularyConfig(null, null, List.of(), Map.of(),
                List.of(), List.of(), List.of(new CustomAttributePredicate("score", List.of("Project"),
                        ValueType.NUMBER, null, null, BigDecimal.ZERO, null))));
        var def = v.attribute("score", "Project").orElseThrow();
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            AttributeValues.Checked c = AttributeValues.check(def, "1E+999999999");
            assertThat(c.ok()).isFalse();
        });
    }

    @Test
    void f1b_maxExponentThrowsOutOfMemoryError() {
        BrainVocabulary v = new VocabularyRegistry().forBrain(new VocabularyConfig(null, null, List.of(), Map.of(),
                List.of(), List.of(), List.of(new CustomAttributePredicate("score", List.of("Project"),
                        ValueType.NUMBER, null, null, null, null))));
        var def = v.attribute("score", "Project").orElseThrow();
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertThat(AttributeValues.check(def, "1E+2147483647").ok()).isFalse();
        });
    }

    // F2: secret obfuscated with a zero-width char / fullwidth letters passes the scanner,
    // but norm() strips/folds it, so the stored normalizedName contains the real token.
    @Test
    void f2_secretHiddenByZeroWidthOrFullwidthIsCaught() {
        CandidateSet c = CandidateSet.builder().entity(self()).build();
        String token = "sk-\u200babcdefghijklmnopqrstuvwxyz0123";
        ValidatedPlan p = validate(ctx(CORE, c), new CreateEntity("e1", "Technology", token, null, List.of()));
        assertThat(p.rejected()).extracting(ValidationIssue::code).contains(RejectionCode.SECRET_DETECTED);
    }

    @Test
    void f2b_fullwidthPasswordIsCaught() {
        assertThat(new SecretScanner().containsSecret("ｐａｓｓｗｏｒｄ：hunter2")).isTrue();
    }

    // F3: diacritic removal of all \p{Mn} merges distinct non-Latin names
    @Test
    void f3_japaneseVoicingMarksAreNotDiacritics() {
        assertThat(Normalizer.norm("ガス")).isNotEqualTo(Normalizer.norm("カス")); // gas vs dregs
        assertThat(Normalizer.norm("मुंबई")).isNotEqualTo(Normalizer.norm("मबई"));
    }

    // F4: STRING valueNorm truncated to 200 code points -> distinct long values share activeKey
    @Test
    void f4_distinctLongRationalesDoNotCollide() {
        var def = CORE.attribute("rationale", "Decision").orElseThrow();
        String prefix = "x".repeat(200);
        String a = AttributeValues.check(def, prefix + " because of cost").valueNorm();
        String b = AttributeValues.check(def, prefix + " because of licensing").valueNorm();
        assertThat(a).isNotEqualTo(b);
    }

    // F4b: with an existing candidate claim, the second distinct rationale is annotated as a reinforcement
    @Test
    void f4b_longRationaleIsWronglyReinforcement() {
        String prefix = "y".repeat(200);
        String existingNorm = AttributeValues.check(CORE.attribute("rationale", "Decision").orElseThrow(),
                prefix + " cost").valueNorm();
        CandidateSet c = CandidateSet.builder().entity(self())
                .entity(entity("k1", "id-d", "Decision", "Use Neo4j"))
                .claim(attribute("c1", "id-c1", "id-d", "rationale", ValueType.STRING, existingNorm, Origin.LLM))
                .build();
        ValidatedPlan p = validate(ctx(CORE, c), AssertClaim.attribute(EntityTarget.handle("k1"), "rationale",
                prefix + " licensing", "s", "GraphSpindle uses Neo4j", 0.9));
        assertThat(p.adjusted()).extracting(Adjustment::kind).doesNotContain(Adjustment.Kind.REINFORCEMENT);
    }

    // F5: in-proposal creates of compatible types with the same name are not unified (duplicates)
    @Test
    void f5_compatibleTypesInOneProposalAreOneEntity() {
        CandidateSet c = CandidateSet.builder().entity(self()).build();
        ValidatedPlan p = validate(ctx(CORE, c),
                new CreateEntity("e1", "Technology", "Neo4j", null, List.of()),
                new CreateEntity("e2", "Concept", "Neo4j", null, List.of()),
                AssertClaim.relation(EntityTarget.ref("e1"), "IS_A", EntityTarget.ref("e2"), "s",
                        "GraphSpindle uses Neo4j", 0.9));
        assertThat(p.commands().stream().filter(x -> x instanceof CreateEntityCommand).count()).isEqualTo(1);
    }

    // F6: custom type whose name differs from a core type only in case collides in canonicalKey
    @Test
    void f6_customTypeCaseCollisionIsRejected() {
        VocabularyConfig cfg = new VocabularyConfig(null, null, List.of(), Map.of(),
                List.of(new CustomEntityType("TECHNOLOGY", "dup", null, List.of()),
                        new CustomEntityType("Recipe", "a recipe", null, List.of()),
                        new CustomEntityType("RECIPE", "a recipe", null, List.of())),
                List.of(), List.of());
        assertThat(new VocabularyConfigValidator().validate(cfg)).isNotEmpty();
    }

    // F7: retract_claim / propose_merge on entities of a disabled (read-only) type are accepted
    @Test
    void f7_readOnlyEntitiesCannotBeRetractedOrMerged() {
        BrainVocabulary v = new VocabularyRegistry().forBrain(new VocabularyConfig(null,
                List.of("Person", "Project"), List.of(), Map.of(), List.of(), List.of(), List.of()));
        CandidateSet c = CandidateSet.builder().entity(self())
                .entity(entity("k1", "id-neo", "Technology", "Neo4j"))
                .entity(entity("k2", "id-neo2", "Technology", "Neo4j DB"))
                .claim(relation("c1", "id-c1", "id-neo", "USES", "id-x", Origin.LLM)).build();
        ValidatedPlan p = validate(ctx(v, c),
                new RetractClaim("c1", "wrong", "GraphSpindle uses Neo4j"),
                new ProposeMerge(EntityTarget.handle("k1"), EntityTarget.handle("k2"), "same"));
        assertThat(p.commands()).isEmpty();
    }

    // F8: URL attribute with embedded credentials is accepted and stored
    @Test
    void f8_urlWithUserinfoIsRejected() {
        var def = CORE.attribute("homepage", "Project").orElseThrow();
        assertThat(AttributeValues.check(def, "https://admin:hunter2@example.com/").ok()).isFalse();
    }

    // F9: evidence only needs 3 chars of the content, unrelated to the claim
    @Test
    void f9_unrelatedThreeCharEvidenceDoesNotSupportAnInventedClaim() {
        CandidateSet c = CandidateSet.builder().entity(self())
                .entity(entity("k1", "id-gs", "Project", "GraphSpindle"))
                .entity(entity("k2", "id-pg", "Technology", "PostgreSQL")).build();
        ValidatedPlan p = validate(ctx(CORE, c), AssertClaim.relation(EntityTarget.handle("k1"), "USES",
                EntityTarget.handle("k2"), "GraphSpindle uses PostgreSQL", "the", 0.95));
        assertThat(p.rejected()).extracting(ValidationIssue::code).contains(RejectionCode.EVIDENCE_NOT_FOUND);
    }

    // F10: duplicate-key envelope is malformed, but the scanner falls back to a nested object
    @Test
    void f10_duplicateKeyEnvelopeIsNotRescuedByNestedObject() {
        String raw = "{\"summary\":\"a\",\"summary\":\"b\",\"operations\":[],\"x\":{\"operations\":[{\"op\":"
                + "\"create_entity\",\"ref\":\"e1\",\"type\":\"Technology\",\"name\":\"Evil\"}]}}";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProposalParser().parse(raw))
                .isInstanceOf(io.graphspindle.ingestion.ops.MalformedModelOutputException.class);
    }

    // F11: URL normalization when host is null duplicates userinfo and keeps case/port
    @Test
    void f11_urlNormalizationWithUnderscoreHost() {
        assertThat(Normalizer.normalizeUrl("HTTP://u@Exa_mple.com:80/a/"))
                .isEqualTo(Normalizer.normalizeUrl("http://u@exa_mple.com/a"));
        assertThat(Normalizer.normalizeUrl("http://u@exa_mple.com/a")).doesNotContain("u@u@");
    }

    // F12: adjustments are reported for an op that is then rejected
    @Test
    void f12_noAdjustmentsForRejectedOps() {
        CandidateSet c = CandidateSet.builder().entity(self())
                .entity(entity("k1", "id-gs", "Project", "GraphSpindle"))
                .entity(entity("k2", "id-neo", "Technology", "Neo4j")).build();
        ValidationContext cx = ctx(CORE, c).withLimits(Limits.defaults().withMaxClaimsPerSubject(1));
        AssertClaim a = AssertClaim.relation(EntityTarget.handle("k1"), "USES", EntityTarget.handle("k2"), "s",
                "GraphSpindle uses Neo4j", 0.99);
        ValidatedPlan p = validate(cx, a, a);
        assertThat(p.rejected()).hasSize(1);
        assertThat(p.adjusted()).filteredOn(x -> x.opIndex() == 1).isEmpty();
    }

    // F13: flag_conflict accepts an incoming claim unrelated to the flagged claim (different subject/predicate)
    @Test
    void f13_flagConflictMustTargetTheSameSlot() {
        CandidateSet c = CandidateSet.builder().entity(self())
                .entity(entity("k1", "id-gs", "Project", "GraphSpindle"))
                .entity(entity("k2", "id-neo", "Technology", "Neo4j"))
                .entity(entity("k3", "id-mit", "Standard", "MIT"))
                .claim(relation("c1", "id-c1", "id-gs", "LICENSED_UNDER", "id-mit", Origin.MANUAL)).build();
        ValidatedPlan p = validate(ctx(CORE, c), new io.graphspindle.ingestion.ops.FlagConflict("c1",
                AssertClaim.relation(EntityTarget.handle("k2"), "IMPLEMENTS", EntityTarget.handle("k3"), "s",
                        "GraphSpindle uses Neo4j", 0.9), "conflict"));
        assertThat(p.commands()).isEmpty();
    }

    // ---- additional regression tests for the same findings

    @Test
    void f1_largeButBoundedNumbersAreCanonical() {
        var def = new VocabularyRegistry().forBrain(new VocabularyConfig(null, null, List.of(), Map.of(),
                List.of(), List.of(), List.of(new CustomAttributePredicate("score", List.of("Project"),
                        ValueType.NUMBER, null, null, null, null)))).attribute("score", "Project").orElseThrow();
        assertThat(AttributeValues.check(def, "1E+38").value()).isEqualTo("1" + "0".repeat(38));
        assertThat(AttributeValues.check(def, "1E+39").ok()).isFalse();
        assertThat(AttributeValues.check(def, "1E-39").ok()).isFalse();
        assertThat(AttributeValues.check(def, "100E+2147483647").ok()).isFalse();
        assertThat(AttributeValues.check(def, "12.500").value()).isEqualTo("12.5");
    }

    @Test
    void f2_redactRemovesObfuscatedSecrets() {
        SecretScanner scanner = new SecretScanner();
        String redacted = scanner.redact("key sk-\u200babcdefghijklmnopqrstuvwxyz0123 end");
        assertThat(redacted).contains(SecretScanner.REDACTED).doesNotContain("abcdefghijklmnop");
        assertThat(scanner.redact("plain text")).isEqualTo("plain text");
    }

    @Test
    void f3_latinDiacriticsAreStillRemoved() {
        assertThat(Normalizer.norm("Zürich")).isEqualTo("zurich");
        assertThat(Normalizer.norm("ガス")).isEqualTo("ガス");
    }

    @Test
    void f6_caseOnlyDuplicateCustomTypesAreRejected() {
        VocabularyConfig cfg = new VocabularyConfig(null, null, List.of(), Map.of(),
                List.of(new CustomEntityType("Recipe", "a recipe", null, List.of()),
                        new CustomEntityType("RECIPE", "a recipe", null, List.of())),
                List.of(), List.of());
        assertThat(new VocabularyConfigValidator().validate(cfg)).extracting(i -> i.path())
                .containsExactly("customEntityTypes[1].name");
    }

    @Test
    void f8_substantialQuoteWhoseSentenceIgnoresTheClaimIsRejected() {
        CandidateSet c = CandidateSet.builder().entity(self())
                .entity(entity("k1", "id-gs", "Project", "GraphSpindle"))
                .entity(entity("k2", "id-pg", "Technology", "PostgreSQL")).build();
        ValidatedPlan p = validate(ctx(CORE, c), AssertClaim.relation(EntityTarget.handle("k1"), "USES",
                EntityTarget.handle("k2"), "GraphSpindle uses PostgreSQL", "Alice is here", 0.95));
        assertThat(p.rejected()).extracting(ValidationIssue::code).contains(RejectionCode.EVIDENCE_NOT_FOUND);
    }

    @Test
    void f10_objectNestedInAValidNonEnvelopeIsNotUsed() {
        String raw = "{\"note\":{\"operations\":[]}} then {\"operations\":[]}";
        assertThat(new ProposalParser().parse(raw).proposal().operations()).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProposalParser().parse(
                "{\"wrapper\":{\"operations\":[]}}"))
                .isInstanceOf(io.graphspindle.ingestion.ops.MalformedModelOutputException.class);
    }

    @Test
    void f11_authorityIsParsedByHand() {
        assertThat(Normalizer.normalizeUrl("HTTPS://[::1]:443/x/")).isEqualTo("https://[::1]/x");
        assertThat(Normalizer.normalizeUrl("http://Exa_mple.com:8080")).isEqualTo("http://exa_mple.com:8080");
        assertThat(Normalizer.hasUserInfo("https://u@exa_mple.com/")).isTrue();
        assertThat(Normalizer.hasUserInfo("https://example.com/a@b")).isFalse();
    }

    @Test
    void nfkcExpansionPastTwoHundredIsRejectedNotTruncated() {
        CandidateSet c = CandidateSet.builder().entity(self()).build();
        String name = "\ufdfa".repeat(12); // 12 code points, 216 after NFKC
        ValidatedPlan p = validate(ctx(CORE, c), new CreateEntity("e1", "Concept", name, null, List.of()));
        assertThat(p.rejected()).extracting(ValidationIssue::code).containsExactly(RejectionCode.NAME_INVALID);
    }
}
