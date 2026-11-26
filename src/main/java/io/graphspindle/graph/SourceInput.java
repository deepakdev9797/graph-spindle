package io.graphspindle.graph;

import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * A source reference supplied with an ingestion (SCHEMA §5.4 SourceRef). The locator is stored as
 * given and <strong>never dereferenced</strong>.
 */
public record SourceInput(String kind, String locator, String title, String summary, String mediaType,
        String clientContentHash) {

    public static final Set<String> KINDS = Set.of("FILE", "URL", "MESSAGE", "OTHER");

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
