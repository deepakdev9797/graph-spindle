package io.graphspindle.brain;

/**
 * Input for {@link BrainService#create}.
 *
 * @param ownerName display name of the self entity; default "Me"
 * @param config initial config; null means defaults
 */
public record NewBrain(String id, String name, String description, String ownerName, BrainConfig config) {
}
