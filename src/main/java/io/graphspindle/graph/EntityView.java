package io.graphspindle.graph;

import java.util.Map;

/** A stored entity. {@code attributes} are the projected {@code attr_*} properties without the prefix. */
public record EntityView(String id, String type, String name, String normalizedName, String canonicalKey,
        String description, boolean descriptionLocked, String status, String origin, long mentionCount,
        String mergedIntoId, Map<String, Object> attributes) {
}
