package io.graphspindle.llm;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Delimits untrusted text in prompts (ARCHITECTURE §8 "Prompt injection"). Every block of one prompt
 * shares a random per-call nonce:
 *
 * <pre>
 * &lt;&lt;&lt;DATA nonce=3f9c0a7d51e2b846 name=content&gt;&gt;&gt;
 * ...untrusted text...
 * &lt;&lt;&lt;END DATA nonce=3f9c0a7d51e2b846&gt;&gt;&gt;
 * </pre>
 *
 * The prompt tells the model that only a marker carrying this nonce opens or closes a block, so
 * untrusted text cannot fake the end of its block without guessing 64 random bits. As a second line
 * of defense a nonce that occurs anywhere in the wrapped texts is never used.
 */
public final class DataBlocks {

    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String nonce;

    private DataBlocks(String nonce) {
        this.nonce = nonce;
    }

    /** A fresh random nonce that does not occur in any of {@code texts}. */
    public static DataBlocks forTexts(List<String> texts) {
        while (true) {
            byte[] b = new byte[8];
            RANDOM.nextBytes(b);
            String candidate = HexFormat.of().formatHex(b);
            if (texts.stream().filter(Objects::nonNull).noneMatch(t -> t.contains(candidate))) {
                return new DataBlocks(candidate);
            }
        }
    }

    /** Fixed nonce, for tests only. */
    public static DataBlocks withNonce(String nonce) {
        if (!nonce.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("nonce must be 16 hex characters");
        }
        return new DataBlocks(nonce);
    }

    public String nonce() {
        return nonce;
    }

    public String open(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid block name " + name);
        }
        return "<<<DATA nonce=" + nonce + " name=" + name + ">>>";
    }

    public String close() {
        return "<<<END DATA nonce=" + nonce + ">>>";
    }

    /** {@code text} between an opening and a closing marker, each on its own line. */
    public String wrap(String name, String text) {
        String body = text == null ? "" : text;
        if (body.contains(nonce)) {
            throw new IllegalStateException("text contains the block nonce; choose the nonce with forTexts");
        }
        return open(name) + "\n" + body + (body.endsWith("\n") ? "" : "\n") + close();
    }
}
