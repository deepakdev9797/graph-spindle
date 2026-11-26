package io.graphspindle.vocabulary;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A set of entity type names, or {@code Any} (any enabled entity type).
 *
 * @param anyType true for "any enabled type"
 * @param types the explicit type names when {@code anyType} is false (sorted, immutable)
 */
public record TypeSet(boolean anyType, Set<String> types) {

    private static final TypeSet ANY = new TypeSet(true, Set.of());

    public TypeSet {
        types = anyType ? Set.of() : java.util.Collections.unmodifiableSortedSet(new TreeSet<>(types));
    }

    public static TypeSet any() {
        return ANY;
    }

    public static TypeSet of(String... names) {
        return new TypeSet(false, Set.of(names));
    }

    public static TypeSet of(Collection<String> names) {
        return new TypeSet(false, Set.copyOf(names));
    }

    public boolean contains(String type) {
        return anyType || types.contains(type);
    }

    /** Restricts this set to {@code enabled} types (Any stays Any). */
    public TypeSet restrictTo(Set<String> enabled) {
        if (anyType) {
            return this;
        }
        return new TypeSet(false, types.stream().filter(enabled::contains).collect(Collectors.toSet()));
    }

    public boolean isEmpty() {
        return !anyType && types.isEmpty();
    }

    /** "Any" or a comma separated list. */
    public String describe() {
        return anyType ? "Any" : String.join(", ", types);
    }
}
