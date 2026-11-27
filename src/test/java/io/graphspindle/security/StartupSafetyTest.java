package io.graphspindle.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.graphspindle.GraphSpindleApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** IMPLEMENTATION_PLAN M9 {@code StartupSafetyTest}. */
class StartupSafetyTest {

    static final String KEY = "a-sufficiently-long-admin-key-0123456789";

    static SecurityProperties props(boolean enabled, String key) {
        return new SecurityProperties(enabled, key, SecurityProperties.ApiDocsAccess.AUTO,
                new SecurityProperties.RateLimit(300, 30, 20), java.util.List.of(), java.util.List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::", "10.1.2.3", "192.168.1.10", ""})
    void nonLoopbackWithoutAdminKeyIsRefused(String address) {
        assertThatThrownBy(() -> StartupSafety.verify(props(true, null), address))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("GRAPHSPINDLE_ADMIN_API_KEY");
        assertThatThrownBy(() -> StartupSafety.verify(props(false, KEY), address))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("loopback");
        assertThat(StartupSafety.verify(props(true, KEY), address)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "localhost", "127.0.0.2"})
    void loopbackMayRunWithoutKeyOrWithSecurityDisabled(String address) {
        assertThat(StartupSafety.verify(props(true, null), address)).isTrue();
        assertThat(StartupSafety.verify(props(false, null), address)).isTrue();
    }

    @Test
    void shortAdminKeyIsRefused() {
        assertThatThrownBy(() -> StartupSafety.verify(props(true, "short"), "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("too short")
                .hasMessageNotContaining("short\"");
    }

    @Test
    void contextStartupFailsOnAllInterfacesWithoutAdminKey() {
        assertThatThrownBy(() -> run("server.address=0.0.0.0"))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("refusing to start");
    }

    @Test
    void contextStartupFailsWhenSecurityIsDisabledOffLoopback() {
        assertThatThrownBy(() -> run("server.address=0.0.0.0", "graphspindle.security.enabled=false",
                "graphspindle.security.admin-api-key=" + KEY))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("only allowed when server.address");
    }

    /** Command-line arguments: they override application.yml (builder properties would not). */
    private static void run(String... properties) {
        String[] base = {"server.port=0", "graphspindle.graph.initialize-schema=false",
            "management.health.neo4j.enabled=false", "graphspindle.security.admin-api-key="};
        java.util.List<String> args = new java.util.ArrayList<>();
        for (String p : base) {
            args.add("--" + p);
        }
        for (String p : properties) {
            args.add("--" + p);
        }
        new SpringApplicationBuilder(GraphSpindleApplication.class).run(args.toArray(String[]::new)).close();
    }
}
