package io.graphspindle.common;

import java.util.Map;

/** Validation of list limits and keyset cursors shared by REST and MCP. */
public final class Paging {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private Paging() {
    }

    /** {@code null} gives {@code dflt}; below 1 is VALIDATION_FAILED; above {@code max} is LIMIT_EXCEEDED (400). */
    public static int limit(Integer requested, int dflt, int max) {
        if (requested == null) {
            return dflt;
        }
        if (requested < 1) {
            throw GraphSpindleException.validation("limit must be at least 1");
        }
        if (requested > max) {
            throw new GraphSpindleException(ErrorCode.LIMIT_EXCEEDED, "limit must be at most " + max,
                    Map.of("max", max), null);
        }
        return requested;
    }

    /** A cursor is the last id of the previous page (lowercase hex and dashes). */
    public static String cursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        if (cursor.length() > 64 || !cursor.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                || c == '-')) {
            throw GraphSpindleException.validation("invalid cursor");
        }
        return cursor;
    }

    /** Ids from paths or tool arguments: bounded, otherwise treated as not found by callers. */
    public static boolean plausibleId(String id) {
        return id != null && !id.isEmpty() && id.length() <= 64;
    }
}
