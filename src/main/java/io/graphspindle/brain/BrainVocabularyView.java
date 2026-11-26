package io.graphspindle.brain;

import io.graphspindle.vocabulary.BrainVocabulary;

/** A brain together with its resolved vocabulary. */
public record BrainVocabularyView(Brain brain, BrainVocabulary vocabulary) {
}
