package io.graphspindle.graph;

/** A fulltext hit. */
public record ScoredEntity(EntityView entity, double score) {
}
