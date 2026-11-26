package io.graphspindle.vocabulary;

/** Per-subject cardinality of a predicate. ONE is enforced in the database through {@code slotKey}. */
public enum Cardinality {
    ONE,
    MANY
}
