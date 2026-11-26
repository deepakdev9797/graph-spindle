package io.graphspindle.graph;

import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A source reference supplied with an ingestion (SCHEMA §5.4 SourceRef). The locator is stored as
 * given and <strong>never dereferenced</strong>.
 */
public record SourceInput(String kind, String locator, String title, String summary, String mediaType,
        String clientContentHash) {

    public static final Set<String> KINDS = Set.of("FILE", "URL", "MESSAGE", "OTHER");

    /** RFC 6838 restricted names, {@code type/subtype}, no parameters. */
    static final Pattern MEDIA_TYPE = Pattern.compile(
            "[a-z0-9][a-z0-9!#$&^_.+-]{0,62}/[a-z0-9][a-z0-9!#$&^_.+-]{0,62}");
    private static final String ALGORITHM = "(?:(?:sha-?(?:1|224|256|384|512)|sha3-(?:224|256|384|512)|md5|blake2[bs]"
            + "|blake3|xxh(?:64|128))[:-])?";
    /** An optional algorithm prefix and 32-128 hex digits. */
    static final Pattern HEX_HASH = Pattern.compile("(?i)" + ALGORITHM + "[0-9a-f]{32,128}");
    /** An optional algorithm prefix and a standard base64 digest (16-64 bytes), as in SRI. */
    static final Pattern BASE64_HASH = Pattern.compile("(?i)" + ALGORITHM + "[A-Za-z0-9+/]{22,88}={0,2}");

    public SourceInput {
        kind = kind == null ? null : kind.strip().toUpperCase(Locale.ROOT);
        if (kind == null || !KINDS.contains(kind)) {
            throw GraphSpindleException.validation("source kind must be one of " + KINDS);
        }
        if (locator == null || locator.isBlank() || Normalizer.codePointLength(locator) > 2000) {
            throw GraphSpindleException.validation("source locator is required (at most 2000 characters)");
        }
        check("title", title, 200);
        check("summary", summary, 2000);
        check("mediaType", mediaType, 100);
        check("clientContentHash", clientContentHash, 128);
        // free text here would be stored unredacted: only well-formed values are accepted (ADR-031)
        mediaType = mediaType == null || mediaType.isBlank() ? null : mediaType.strip().toLowerCase(Locale.ROOT);
        if (mediaType != null && !MEDIA_TYPE.matcher(mediaType).matches()) {
            throw GraphSpindleException.validation("source mediaType must be a media type type/subtype without"
                    + " parameters, e.g. text/markdown");
        }
        clientContentHash = clientContentHash == null || clientContentHash.isBlank() ? null : clientContentHash.strip();
        if (clientContentHash != null && !HEX_HASH.matcher(clientContentHash).matches()
                && !BASE64_HASH.matcher(clientContentHash).matches()) {
            throw GraphSpindleException.validation("source contentHash must be a hex digest (32-128 hex digits) or"
                    + " a base64 digest, optionally prefixed with its algorithm (sha256:, sha256-)");
        }
    }

    /** SCHEMA §7.5 locator normalization. */
    public String locatorNorm() {
        return switch (kind) {
            case "URL" -> Normalizer.normalizeUrl(locator);
            case "FILE" -> Normalizer.normalizeFilePath(locator);
            default -> Normalizer.normUntruncated(locator).isEmpty() ? locator.strip()
                    : Normalizer.normUntruncated(locator);
        };
    }

    private static void check(String field, String value, int max) {
        if (value != null && Normalizer.codePointLength(value) > max) {
            throw GraphSpindleException.validation("source " + field + " must be at most " + max + " characters");
        }
    }
}
