package io.graphspindle.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Startup loading of the YAML vocabulary and the external override (ADR-025). */
class VocabularyConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(VocabularyConfiguration.class);

    @Test
    void withoutLocationTheBundledProfileIsUsed() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(CoreProfile.class)).isSameAs(CoreVocabulary.DEFAULT);
            assertThat(ctx.getBean(VocabularyRegistry.class).core().entityTypes()).hasSize(15);
        });
    }

    @Test
    void locationIsMergedOverTheBundledProfile() {
        runner.withPropertyValues("graphspindle.vocabulary.location=classpath:vocabulary/override-valid.yml")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    BrainVocabulary core = ctx.getBean(VocabularyRegistry.class).core();
                    assertThat(core.isEnabledType("Vehicle")).isTrue();
                    assertThat(core.isEnabledType("Preference")).isFalse();
                    assertThat(core.relation("DRIVES")).isPresent();
                });
    }

    @Test
    void invalidOrMissingFileFailsFast() {
        runner.withPropertyValues("graphspindle.vocabulary.location=classpath:vocabulary/override-invalid.yml")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .rootCause().isInstanceOf(InvalidVocabularyDefinitionException.class)
                        .hasMessageContaining("description: is required"));
        runner.withPropertyValues("graphspindle.vocabulary.location=file:/nonexistent/vocabulary.yml")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("does not exist"));
    }
}
