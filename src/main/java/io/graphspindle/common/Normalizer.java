package io.graphspindle.common;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer.Form;
import java.util.Locale;
import java.util.Optional;

/**
 * Deterministic, pure name and locator normalization (SCHEMA §7.3 and §7.5).
 *
 * <p>All methods are linear in the input length and use no backtracking regular expressions, so
 * they are safe on untrusted, very large input. Callers should still bound input length first.
 */
public final class Normalizer {

    /** Maximum length of a normalized name, in Unicode code points. */
    public static final int MAX_NAME_CODE_POINTS = 200;

    private static final String OPENING = "\"'`([{<“‘«„‚‹「『";
    private static final String CLOSING = "\"'`)]}>”’»“‘›」』";
    private static final String TRAILING_PUNCTUATION = ".,;:!?";

    private Normalizer() {
    }

    /**
     * {@code norm(s)} from SCHEMA §7.3. Returns an empty string when nothing is left. Symbols such
     * as {@code + # . / -} are kept, so "C++", "C#" and "C" stay distinct.
     */
    public static String norm(String input) {
        // 8. truncate to 200 code points
        return truncate(normUntruncated(input), MAX_NAME_CODE_POINTS).strip();
    }

    /**
     * Steps 1 to 7 of {@code norm(s)} without the final truncation. Validators use this form and
     * <em>reject</em> names whose result is longer than {@link #MAX_NAME_CODE_POINTS} (NFKC can
     * expand a short input), so truncation can never make two distinct names share a canonical key.
     * Attribute {@code valueNorm}s and locator keys use it so long values never collide.
     */
    public static String normUntruncated(String input) {
        if (input == null) {
            return "";
        }
        // 1. Unicode NFKC
        String s = java.text.Normalizer.normalize(input, Form.NFKC);
        // 2. strip control and format characters (zero-width, bidi overrides, BOM ...);
        //    whitespace controls (tab, newline) become spaces so words do not fuse.
        // 3. trim and collapse whitespace
        s = stripControlsAndCollapseWhitespace(s);
        // 4. lowercase, locale independent
        s = s.toLowerCase(Locale.ROOT);
        // 5. strip surrounding quotes/brackets and trailing punctuation
        s = stripSurroundings(s);
        // 6. remove diacritics
        s = removeDiacritics(s);
        // 7. remove a leading "the "
        if (s.startsWith("the ") && s.length() > 4) {
            s = s.substring(4).strip();
            s = stripSurroundings(s);
        }
        return s.strip();
    }

    /** {@link #norm(String)} as an optional that is empty when the result is empty. */
    public static Optional<String> normalizeName(String input) {
        String n = norm(input);
        return n.isEmpty() ? Optional.empty() : Optional.of(n);
    }

    /**
     * The compact matching key of a name (M7 candidate lookup, ADR-030): {@code norm(s)} without a
     * leading "the " and without anything but letters, digits, {@code +} and {@code #}, so "Graph
     * Spindle", "graph-spindle", "The GraphSpindle" and "GraphSpindle." share one key while "C++",
     * "C#" and "C" stay distinct. Empty when nothing is left. Only used to find candidates, never as
     * an identity.
     */
    public static String compact(String input) {
        String n = norm(input);
        if (n.startsWith("the ") && n.length() > 4) {
            n = n.substring(4);
        }
        StringBuilder b = new StringBuilder(n.length());
        n.codePoints().filter(cp -> Character.isLetterOrDigit(cp) || cp == '+' || cp == '#')
                .forEach(b::appendCodePoint);
        return b.toString();
    }

    /** {@code type.toLowerCase(ROOT) + ":" + normalizedName} (SCHEMA §7.3). */
    public static String canonicalKey(String type, String normalizedName) {
        if (type == null || type.isBlank() || normalizedName == null || normalizedName.isEmpty()) {
            throw new IllegalArgumentException("type and normalizedName are required");
        }
        return type.toLowerCase(Locale.ROOT) + ":" + normalizedName;
    }

    /** Whitespace-only cleanup: NFKC, controls removed, whitespace collapsed. Case is preserved. */
    public static String normalizeWhitespace(String input) {
        if (input == null) {
            return "";
        }
        return stripControlsAndCollapseWhitespace(java.text.Normalizer.normalize(input, Form.NFKC));
    }

    /**
     * URL normalization (SCHEMA §7.5): lowercase scheme and host, drop the default port and the
     * fragment, keep the query, remove a trailing {@code /} on the path. Returns the trimmed input
     * unchanged if it cannot be parsed as a hierarchical URI.
     */
    public static String normalizeUrl(String input) {
        if (input == null) {
            return "";
        }
        String trimmed = input.strip();
        try {
            URI uri = new URI(trimmed);
            if (uri.isOpaque() || uri.getScheme() == null) {
                return trimmed;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.equals("/")) {
                path = "";
            }
            StringBuilder out = new StringBuilder(scheme).append(':');
            String authority = uri.getRawAuthority();
            if (authority != null) {
                out.append("//").append(normalizeAuthority(scheme, authority));
            }
            out.append(path);
            if (uri.getRawQuery() != null) {
                out.append('?').append(uri.getRawQuery());
            }
            return out.toString();
        } catch (URISyntaxException e) {
            return trimmed;
        }
    }

    /**
     * True when the URL has an authority with user information ({@code user:password@host}). The
     * authority is parsed by hand because {@link URI} leaves host and user info null for
     * registry-based authorities such as hosts containing {@code _}.
     */
    public static boolean hasUserInfo(String url) {
        if (url == null) {
            return false;
        }
        try {
            String authority = new URI(url.strip()).getRawAuthority();
            return authority != null && authority.indexOf('@') >= 0;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * {@code [userinfo@]host[:port]}, parsed by hand so that registry-based authorities (for which
     * {@link URI#getHost()} is null) are normalized the same way: host lowercased, default port dropped.
     */
    private static String normalizeAuthority(String scheme, String authority) {
        int at = authority.lastIndexOf('@');
        String userInfo = at >= 0 ? authority.substring(0, at) : null;
        String hostPort = at >= 0 ? authority.substring(at + 1) : authority;
        String host = hostPort;
        String port = null;
        int colon = hostPort.lastIndexOf(':');
        int bracket = hostPort.lastIndexOf(']');
        if (colon > bracket) {
            host = hostPort.substring(0, colon);
            port = hostPort.substring(colon + 1);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (port != null && (port.isEmpty() || (port.equals("80") && scheme.equals("http"))
                || (port.equals("443") && scheme.equals("https")))) {
            port = null;
        }
        StringBuilder out = new StringBuilder();
        if (userInfo != null) {
            out.append(userInfo).append('@');
        }
        out.append(host);
        if (port != null) {
            out.append(':').append(port);
        }
        return out.toString();
    }

    /**
     * File path normalization (SCHEMA §7.5): separators unified to {@code /}, {@code ./} segments
     * and duplicate {@code /} collapsed. Case is preserved. {@code ..} is kept as given.
     */
    public static String normalizeFilePath(String input) {
        if (input == null) {
            return "";
        }
        String s = input.strip().replace('\\', '/');
        boolean absolute = s.startsWith("/");
        StringBuilder out = new StringBuilder();
        for (String segment : s.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(segment);
        }
        return absolute ? "/" + out : out.toString();
    }

    /** Truncates to at most {@code maxCodePoints} code points without splitting surrogate pairs. */
    public static String truncate(String s, int maxCodePoints) {
        if (s.length() <= maxCodePoints) {
            return s;
        }
        if (s.codePointCount(0, s.length()) <= maxCodePoints) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxCodePoints));
    }

    /** Length in Unicode code points. */
    public static int codePointLength(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    private static String stripControlsAndCollapseWhitespace(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean pendingSpace = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = true;
                continue;
            }
            int t = Character.getType(cp);
            if (t == Character.CONTROL || t == Character.FORMAT) {
                continue;
            }
            if (pendingSpace && !out.isEmpty()) {
                out.append(' ');
            }
            pendingSpace = false;
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static String stripSurroundings(String s) {
        String previous;
        do {
            previous = s;
            int end = s.length();
            while (end > 0 && TRAILING_PUNCTUATION.indexOf(s.charAt(end - 1)) >= 0) {
                end--;
            }
            s = s.substring(0, end).strip();
            if (s.length() >= 2) {
                int open = OPENING.indexOf(s.charAt(0));
                int close = CLOSING.indexOf(s.charAt(s.length() - 1));
                if (open >= 0 && close >= 0 && pairMatches(s.charAt(0), s.charAt(s.length() - 1))) {
                    s = s.substring(1, s.length() - 1).strip();
                }
            }
        } while (!s.equals(previous));
        return s;
    }

    private static boolean pairMatches(char open, char close) {
        return switch (open) {
            case '(' -> close == ')';
            case '[' -> close == ']';
            case '{' -> close == '}';
            case '<' -> close == '>';
            case '「' -> close == '」';
            case '『' -> close == '』';
            // any quote-like opener pairs with any quote-like closer (models mix styles)
            default -> isQuote(open) && isQuote(close);
        };
    }

    private static boolean isQuote(char c) {
        return "\"'`“”‘’«»„‚‹›".indexOf(c) >= 0;
    }

    /**
     * NFD, drop the code points of the combining <em>diacritical</em> mark blocks, NFC. Other
     * non-spacing marks are meaningful letters in their scripts (Japanese voicing marks, Indic vowel
     * signs, Hebrew and Arabic vowel points ...) and are kept, so ガス and カス stay distinct.
     */
    public static String removeDiacritics(String s) {
        String decomposed = java.text.Normalizer.normalize(s, Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); ) {
            int cp = decomposed.codePointAt(i);
            i += Character.charCount(cp);
            if (!isCombiningDiacritic(cp)) {
                out.appendCodePoint(cp);
            }
        }
        return java.text.Normalizer.normalize(out, Form.NFC);
    }

    /** U+0300–036F, U+1AB0–1AFF, U+1DC0–1DFF, U+20D0–20FF and U+FE20–FE2F. */
    static boolean isCombiningDiacritic(int cp) {
        return (cp >= 0x0300 && cp <= 0x036F) || (cp >= 0x1AB0 && cp <= 0x1AFF) || (cp >= 0x1DC0 && cp <= 0x1DFF)
                || (cp >= 0x20D0 && cp <= 0x20FF) || (cp >= 0xFE20 && cp <= 0xFE2F);
    }
}
