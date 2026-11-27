package io.graphspindle.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SystemInfoServiceTest {

    @ParameterizedTest
    @CsvSource({"none,,false", "ollama,localhost,false", "ollama,127.0.0.1,false", "ollama,[::1],false",
        "openai,127.0.0.1,false", "openai,api.openai.com,true", "anthropic,api.anthropic.com,true",
        "ollama,ollama,true", "ollama,192.168.1.20,true", "fake,,true"})
    void dataLeavesTheMachineUnlessTheHostIsLoopback(String provider, String host, boolean leaves) {
        assertThat(SystemInfoService.leavesMachine(provider, host)).isEqualTo(leaves);
    }
}
