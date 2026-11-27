package io.graphspindle.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API key tokens {@code gsk_<keyId>_<secret>} (ADR-033): a 16-hex-character public key id and a
 * 256-bit random secret in base64url (43 characters). Only {@code sha256(secret)} is stored: the
 * secret is uniformly random with 256 bits of entropy, so a slow password hash adds nothing against
 * brute force, and a fast hash keeps per-request verification cheap.
 */
public final class ApiKeyTokens {

    public static final String PREFIX = "gsk_";
    private static final Pattern TOKEN = Pattern.compile("gsk_([0-9a-f]{16})_([A-Za-z0-9_-]{43})");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyTokens() {
    }

    public record Parsed(String keyId, String secret) {

        @Override
        public String toString() {
            return "Parsed[keyId=" + keyId + ", secret=<redacted>]";
        }
    }

    public record Generated(String keyId, String token, String secretHash) {

        @Override
        public String toString() {
            return "Generated[keyId=" + keyId + ", token=<redacted>]";
        }
    }

    public static Generated generate() {
        byte[] id = new byte[8];
        byte[] secret = new byte[32];
        RANDOM.nextBytes(id);
        RANDOM.nextBytes(secret);
        String keyId = HexFormat.of().formatHex(id);
        String s = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        return new Generated(keyId, PREFIX + keyId + "_" + s, sha256Hex(s));
    }

    public static Optional<Parsed> parse(String token) {
        if (token == null || token.length() > 100) {
            return Optional.empty();
        }
        Matcher m = TOKEN.matcher(token);
        return m.matches() ? Optional.of(new Parsed(m.group(1), m.group(2))) : Optional.empty();
    }

    public static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    /** Constant-time comparison of two digests. */
    public static boolean matches(byte[] expected, byte[] actual) {
        return MessageDigest.isEqual(expected, actual);
    }
}
