package io.graphspindle.ingestion.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecretScannerTest {

    private final SecretScanner scanner = new SecretScanner();

    @ParameterizedTest
    @ValueSource(strings = {
            "-----BEGIN RSA PRIVATE KEY-----",
            "my password: hunter2",
            "PASSWORD=hunter2",
            "api_key = 0123456789abcdef",
            "use sk-proj-abcdefghijklmnopqrstuvwxyz0123",
            "Authorization: Bearer abcdefghijklmnopqrstuvwxyz012345",
            "gsk_k1abcdefgh_secretsecretsecret",
            "AKIAIOSFODNN7EXAMPLE",
            "ghp_abcdefghijklmnopqrstuvwxyz0123456789",
            "xoxb-1234567890-abcdefghij",
            "AIzaSyA-1234567890abcdefghijklmnopqrstu",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"})
    void detectsCommonSecretFormats(String text) {
        assertThat(scanner.containsSecret(text)).isTrue();
        assertThat(scanner.redact(text)).contains(SecretScanner.REDACTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GraphSpindle uses Neo4j", "The password policy requires 12 characters",
            "sk-short", "ask-me-anything", "API keys are stored hashed", "Bearer tokens are used", ""})
    void ignoresOrdinaryText(String text) {
        assertThat(scanner.containsSecret(text)).isFalse();
    }

    @Test
    void redactsInPlace() {
        assertThat(scanner.redact("key AKIAIOSFODNN7EXAMPLE end")).isEqualTo("key [REDACTED] end");
        assertThat(scanner.redact(null)).isNull();
    }

    @Test
    void brainSpecificPatternsAreAdded() {
        SecretScanner custom = new SecretScanner(List.of(Pattern.compile("ACME-[0-9]{6}")));
        assertThat(custom.containsSecret("badge ACME-123456")).isTrue();
        assertThat(scanner.containsSecret("badge ACME-123456")).isFalse();
    }

    @Test
    void isFastOnLargeAdversarialInput() {
        String text = "password ".repeat(100_000) + "sk-" + "a".repeat(19) + " bearer " + "x".repeat(19);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThat(scanner.containsSecret(text)).isFalse());
    }
}
