package io.graphspindle.api.rest;

import io.graphspindle.common.GraphSpindleException;

/** {@code ETag}/{@code If-Match} carry the brain {@code configVersion} as a quoted integer. */
final class ETags {

    private ETags() {
    }

    static String of(long configVersion) {
        return "\"" + configVersion + "\"";
    }

    /** Parses {@code "3"}, {@code W/"3"} or {@code 3}; null when absent. */
    static Long parseIfMatch(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String v = header.strip();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw GraphSpindleException.validation("If-Match must be a configVersion");
        }
    }
}
