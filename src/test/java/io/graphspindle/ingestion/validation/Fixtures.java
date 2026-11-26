package io.graphspindle.ingestion.validation;

import io.graphspindle.common.ClaimKind;
import io.graphspindle.common.Normalizer;
import io.graphspindle.common.Origin;
import io.graphspindle.common.Polarity;
import io.graphspindle.ingestion.ops.ProposalParser;
import io.graphspindle.vocabulary.BrainVocabulary;
import io.graphspindle.vocabulary.ValueType;
import io.graphspindle.vocabulary.VocabularyRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** Shared test data for validation tests. */
final class Fixtures {

    static final BrainVocabulary CORE = new VocabularyRegistry().core();
    static final ProposalParser PARSER = new ProposalParser();
    static final OperationValidator VALIDATOR = new OperationValidator();

    static final String README_CONTENT =
            "I am building GraphSpindle in Java. It uses Neo4j and Spring AI. I decided to use Apache 2.0.";

    private Fixtures() {
    }

    static EntityCandidate self() {
        return new EntityCandidate("P0", "id-self", "Person", "Me", "@self", "person:@self", Set.of(), false, Set.of());
    }

    static EntityCandidate entity(String handle, String id, String type, String name, String... mentionTypes) {
        String norm = Normalizer.norm(name);
        return new EntityCandidate(handle, id, type, name, norm, Normalizer.canonicalKey(type, norm), Set.of(norm),
                false, Set.of(mentionTypes));
    }

    static EntityCandidate locked(String handle, String id, String type, String name) {
        String norm = Normalizer.norm(name);
        return new EntityCandidate(handle, id, type, name, norm, Normalizer.canonicalKey(type, norm), Set.of(norm),
                true, Set.of());
    }

    static ClaimCandidate relation(String handle, String id, String subjectId, String predicate, String objectId,
            Origin origin) {
        return new ClaimCandidate(handle, id, subjectId, predicate, ClaimKind.RELATION, objectId, null, null,
                Polarity.POSITIVE, origin);
    }

    static ClaimCandidate attribute(String handle, String id, String subjectId, String predicate, ValueType type,
            String valueNorm, Origin origin) {
        return new ClaimCandidate(handle, id, subjectId, predicate, ClaimKind.ATTRIBUTE, null, type, valueNorm,
                Polarity.POSITIVE, origin);
    }

    static ValidationContext context(CandidateSet candidates, String content, List<String> sources) {
        return ValidationContext.of("dev", CORE, candidates, content, sources);
    }

    static ValidatedPlan run(String rawModelOutput, ValidationContext ctx) {
        return VALIDATOR.validate(PARSER.parse(rawModelOutput), ctx);
    }

    /** Wraps operation JSON objects in a proposal envelope. */
    static String ops(String... operations) {
        return "{\"summary\":\"test\",\"operations\":[" + String.join(",", operations) + "]}";
    }

    static String resource(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/proposals/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
