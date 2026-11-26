package io.graphspindle.ingestion.validation;

import io.graphspindle.common.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects and redacts credentials in text (SCHEMA §6.5). The default patterns cover common API-key
 * and token formats, PEM blocks and {@code password:}/{@code password=}. Brains may add patterns.
 * All patterns are free of nested quantifiers, so matching is linear in the input length.
 */
public final class SecretScanner {

    public static final String REDACTED = "[REDACTED]";

    public static final List<Pattern> DEFAULT_PATTERNS = List.of(
            Pattern.compile("-----BEGIN"),
            Pattern.compile("(?i)password\\s*[:=]"),
            Pattern.compile("(?i)\\b(?:api[_-]?key|secret[_-]?key|access[_-]?token|auth[_-]?token)\\s*[:=]\\s*\\S{8,}"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("\\bgsk_[A-Za-z0-9_-]{8,}"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}"),
            Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{20,}"));

    private final List<Pattern> patterns;

    public SecretScanner() {
        this(List.of());
    }

    /** Default patterns plus brain-specific ones. */
    public SecretScanner(List<Pattern> extraPatterns) {
        List<Pattern> all = new ArrayList<>(DEFAULT_PATTERNS);
        all.addAll(extraPatterns);
        this.patterns = List.copyOf(all);
    }

    /**
     * True when the text, or its folded form ({@link #fold}: NFKC with control and format characters
     * removed), matches a pattern. Scanning the folded form too means a secret split by zero-width
     * characters or written in full-width letters is caught: names and values are stored after the
     * same folding, so the stored form would otherwise contain the real token.
     */
    public boolean containsSecret(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return matchesAny(text) || matchesAny(fold(text));
    }

    /**
     * Replaces every match with {@value #REDACTED}. When a secret is only visible in the folded
     * form, the folded text is redacted and returned instead (formatting is lost, the secret too).
     */
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = replaceAll(text);
        String folded = fold(out);
        return matchesAny(folded) ? replaceAll(folded) : out;
    }

    /** NFKC, control and format characters removed, whitespace collapsed (case is preserved). */
    static String fold(String text) {
        return Normalizer.normalizeWhitespace(text);
    }

    private boolean matchesAny(String text) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private String replaceAll(String text) {
        String out = text;
        for (Pattern p : patterns) {
            Matcher m = p.matcher(out);
            if (m.find()) {
                out = m.replaceAll(Matcher.quoteReplacement(REDACTED));
            }
        }
        return out;
    }
}
