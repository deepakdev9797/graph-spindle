package io.graphspindle.vocabulary;

import io.graphspindle.common.Normalizer;
import io.graphspindle.common.PartialDate;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Checks and canonicalizes literal values of attribute claims (SCHEMA §6.4) and computes their
 * {@code valueNorm} (SCHEMA §7.4).
 */
public final class AttributeValues {

    /** Hard upper bound on any literal before type-specific checks. */
    public static final int MAX_VALUE_LENGTH = 2000;

    /** Maximum significant digits and maximum absolute scale of a NUMBER value. */
    public static final int MAX_NUMBER_DIGITS = 38;

    private AttributeValues() {
    }

    /**
     * Result of a value check.
     *
     * @param value canonical value to store, null when invalid
     * @param valueNorm normalized value for keys, null when invalid
     * @param error reason when invalid, else null
     * @param engineOnly true when the value is valid but reserved for the engine
     */
    public record Checked(String value, String valueNorm, String error, boolean engineOnly) {

        public boolean ok() {
            return error == null && !engineOnly;
        }

        static Checked ok(String value, String norm) {
            return new Checked(value, norm, null, false);
        }

        static Checked invalid(String error) {
            return new Checked(null, null, error, false);
        }
    }

    /** Validates {@code raw} against the definition and returns the canonical value. */
    public static Checked check(AttributePredicateDef def, String raw) {
        if (raw == null || raw.isBlank()) {
            return Checked.invalid("value is required");
        }
        if (raw.length() > MAX_VALUE_LENGTH) {
            return Checked.invalid("value is too long");
        }
        String v = raw.strip();
        return switch (def.valueType()) {
            case ENUM -> {
                String token = v.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
                if (!def.enumValues().contains(token)) {
                    yield Checked.invalid("value must be one of " + def.llmEnumValues());
                }
                if (def.engineOnlyValues().contains(token)) {
                    yield new Checked(token, token, "value " + token + " is set only by the engine", true);
                }
                yield Checked.ok(token, token);
            }
            case STRING -> {
                int max = def.maxLength() == null ? 500 : def.maxLength();
                if (Normalizer.codePointLength(v) > max) {
                    yield Checked.invalid("value must be at most " + max + " characters");
                }
                // untruncated: a 200-code-point prefix must not make distinct values share an activeKey
                String norm = Normalizer.normUntruncated(v);
                yield norm.isEmpty() ? Checked.invalid("value is empty after normalization") : Checked.ok(v, norm);
            }
            case PARTIAL_DATE -> PartialDate.isValid(v) ? Checked.ok(v, v)
                    : Checked.invalid("value must be YYYY, YYYY-MM or YYYY-MM-DD and a real date");
            case BOOLEAN -> {
                String b = v.toLowerCase(Locale.ROOT);
                yield b.equals("true") || b.equals("false") ? Checked.ok(b, b)
                        : Checked.invalid("value must be true or false");
            }
            case NUMBER -> checkNumber(def, v);
            case URL -> checkUrl(def, v);
        };
    }

    private static Checked checkNumber(AttributePredicateDef def, String v) {
        if (v.length() > 64) {
            return Checked.invalid("number is too long");
        }
        BigDecimal n;
        try {
            n = new BigDecimal(v).stripTrailingZeros();
        } catch (NumberFormatException | ArithmeticException e) {
            return Checked.invalid("value must be a decimal number");
        }
        // Bound the plain form before building it: "1E+999999999" is a 12-character input whose
        // toPlainString() has a billion digits. Precision and |scale| <= 38 keep it under ~80 chars.
        if (n.precision() > MAX_NUMBER_DIGITS || Math.abs((long) n.scale()) > MAX_NUMBER_DIGITS) {
            return Checked.invalid("number must have at most " + MAX_NUMBER_DIGITS
                    + " significant digits and an exponent between -" + MAX_NUMBER_DIGITS + " and "
                    + MAX_NUMBER_DIGITS);
        }
        if (def.min() != null && n.compareTo(def.min()) < 0 || def.max() != null && n.compareTo(def.max()) > 0) {
            return Checked.invalid("value is out of range");
        }
        String canonical = n.toPlainString();
        return Checked.ok(canonical, canonical);
    }

    private static Checked checkUrl(AttributePredicateDef def, String v) {
        int max = def.maxLength() == null ? 2000 : def.maxLength();
        if (v.length() > max) {
            return Checked.invalid("URL must be at most " + max + " characters");
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file:"))) {
            return Checked.invalid("URL must start with http://, https:// or file:");
        }
        try {
            new URI(v);
        } catch (URISyntaxException e) {
            return Checked.invalid("URL cannot be parsed");
        }
        if (Normalizer.hasUserInfo(v)) {
            return Checked.invalid("URL must not contain user information (credentials)");
        }
        return Checked.ok(v, Normalizer.normalizeUrl(v));
    }
}
