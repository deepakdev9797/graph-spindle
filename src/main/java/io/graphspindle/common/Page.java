package io.graphspindle.common;

import java.util.List;

/** A keyset page: {@code next} is the cursor (last id) for the following page, or null at the end. */
public record Page<T>(List<T> items, String next) {

    public Page {
        items = List.copyOf(items);
    }

    /** Builds a page from {@code limit + 1} fetched rows. */
    public static <T> Page<T> of(List<T> rows, int limit, java.util.function.Function<T, String> id) {
        if (rows.size() <= limit) {
            return new Page<>(rows, null);
        }
        List<T> items = rows.subList(0, limit);
        return new Page<>(items, id.apply(items.get(items.size() - 1)));
    }
}
