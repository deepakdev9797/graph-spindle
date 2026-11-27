package io.graphspindle.support;

/**
 * The bootstrap admin key every IT context is started with (ADR-033). A test value only: it exists
 * nowhere but in the tests and is not a real secret.
 */
public final class TestKeys {

    public static final String ADMIN = "test-admin-key-0123456789abcdefghijklmnop";

    private TestKeys() {
    }
}
