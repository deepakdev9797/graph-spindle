package io.graphspindle.common;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

/**
 * A partial calendar date {@code YYYY}, {@code YYYY-MM} or {@code YYYY-MM-DD} (SCHEMA §5.4, §6.4).
 * Parsing is done by hand (no regex) and rejects impossible calendar dates.
 */
public record PartialDate(String value) implements Comparable<PartialDate> {

    public PartialDate {
        if (!isValid(value)) {
            throw new IllegalArgumentException("not a valid partial date");
        }
    }

    /** Parses a partial date, returning empty for anything that is not a valid one. */
    public static Optional<PartialDate> parse(String raw) {
        return isValid(raw) ? Optional.of(new PartialDate(raw)) : Optional.empty();
    }

    /** True for {@code YYYY}, {@code YYYY-MM} or {@code YYYY-MM-DD} denoting a real calendar date. */
    public static boolean isValid(String raw) {
        if (raw == null) {
            return false;
        }
        int len = raw.length();
        if (len != 4 && len != 7 && len != 10) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);
            boolean dash = i == 4 || i == 7;
            if (dash ? c != '-' : (c < '0' || c > '9')) {
                return false;
            }
        }
        try {
            int year = Integer.parseInt(raw.substring(0, 4));
            if (len >= 7) {
                int month = Integer.parseInt(raw.substring(5, 7));
                YearMonth.of(year, month);
                if (len == 10) {
                    LocalDate.of(year, month, Integer.parseInt(raw.substring(8, 10)));
                }
            }
        } catch (DateTimeException e) {
            return false;
        }
        return true;
    }

    /** Sortable key padded to {@code YYYY-MM-DD} using {@code -01} for missing parts. */
    public String sortKey() {
        return switch (value.length()) {
            case 4 -> value + "-01-01";
            case 7 -> value + "-01";
            default -> value;
        };
    }

    @Override
    public int compareTo(PartialDate other) {
        return sortKey().compareTo(other.sortKey());
    }

    @Override
    public String toString() {
        return value;
    }
}
