package io.graphspindle.support;

import io.graphspindle.llm.FakeChatModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The offline, scripted {@code ChatModel} for ingestion ITs (IMPLEMENTATION_PLAN test conventions). */
@TestConfiguration(proxyBeanMethods = false)
public class FakeLlmTestConfig {

    @Bean
    @Primary
    FakeChatModel fakeChatModel() {
        return FakeChatModel.fromResources();
    }
}
