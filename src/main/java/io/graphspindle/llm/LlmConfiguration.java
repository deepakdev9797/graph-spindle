package io.graphspindle.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the default {@link IngestionLlm}; replace it by declaring another {@code IngestionLlm} bean. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfiguration {

    @Bean
    @ConditionalOnMissingBean(IngestionLlm.class)
    SpringAiIngestionLlm ingestionLlm(ObjectProvider<ChatModel> models, LlmProperties properties) {
        return new SpringAiIngestionLlm(models, properties);
    }
}
