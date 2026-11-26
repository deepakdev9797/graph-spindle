package io.graphspindle.common;

import java.util.Optional;
import java.util.Set;

/**
 * A syntactically valid brain id (SCHEMA §7.1): {@code ^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$},
 * ASCII only, not reserved ({@code admin}, {@code system}, {@code all}, {@code _*}). Every graph
 * store method takes one, so no query can run without a brain. It lives in {@code common}
 * because both {@code brain} and {@code graph} need it (ADR-019).
 */
public record BrainScope(String brainId) {

    public static final int MAX_LENGTH = 63;
    private static final Set<String> RESERVED = Set.of("admin", "system", "all");

    public BrainScope {
        if (!isValid(brainId)) {
            throw GraphSpindleException.validation("invalid brain id: must match ^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$"
                    + " and not be reserved");
        }
    }

    public static BrainScope of(String brainId) {
        return new BrainScope(brainId);
    }

    public static Optional<BrainScope> parse(String brainId) {
        return isValid(brainId) ? Optional.of(new BrainScope(brainId)) : Optional.empty();
    }

    /** Hand-written check (no regex backtracking on untrusted input). */
    public static boolean isValid(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_LENGTH || RESERVED.contains(id)) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean alnum = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            boolean edge = i == 0 || i == id.length() - 1;
            if (!(alnum || (!edge && c == '-'))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return brainId;
    }
}
