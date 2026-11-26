package io.graphspindle.graph;

/** An alias of an entity: normalized form, first surface form seen, and who added the link. */
public record AliasView(String normalized, String display, String origin) {
}
