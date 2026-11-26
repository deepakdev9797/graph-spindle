package io.graphspindle.brain;

/** Partial update for {@link BrainService#update}; null fields are left unchanged. {@code config} replaces the whole config. */
public record BrainUpdate(String name, String description, BrainConfig config) {
}
