package io.graphspindle.graph.internal;

import java.util.HashMap;
import java.util.Map;

/** Parameter map builder that allows null values (unlike {@code Map.of}). */
public final class P {

    private P() {
    }

    public static Map<String, Object> of(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("key/value pairs expected");
        }
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }
}
