package io.graphspindle.vocabulary;

import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Loads the core vocabulary profile at startup (ADR-025). The bundled
 * {@code classpath:vocabulary/core-v1.yml} is always the base; {@code graphspindle.vocabulary.location}
 * (any Spring resource location, e.g. {@code file:/etc/graphspindle/vocabulary.yml}) is merged over
 * it. An unreadable or invalid definition stops the application.
 */
@Configuration(proxyBeanMethods = false)
public class VocabularyConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VocabularyConfiguration.class);

    @Bean
    public CoreProfile coreProfile(@Value("${graphspindle.vocabulary.location:}") String location,
            ResourceLoader resources) {
        if (location == null || location.isBlank()) {
            CoreProfile p = CoreVocabulary.DEFAULT;
            log.info("vocabulary: {}", p);
            return p;
        }
        Resource r = resources.getResource(location.strip());
        if (!r.exists()) {
            throw new InvalidVocabularyDefinitionException(location, List.of("graphspindle.vocabulary.location "
                    + "does not exist"), null);
        }
        try {
            CoreProfile p = VocabularyDefinitionLoader.load(r.getInputStream(), location.strip());
            log.info("vocabulary: {}", p);
            return p;
        } catch (IOException e) {
            throw new InvalidVocabularyDefinitionException(location, List.of("cannot read: " + e.getMessage()), e);
        }
    }

    @Bean
    public VocabularyRegistry vocabularyRegistry(CoreProfile coreProfile) {
        return new VocabularyRegistry(coreProfile);
    }
}
