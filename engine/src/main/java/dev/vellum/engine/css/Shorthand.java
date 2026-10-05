package dev.vellum.engine.css;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A shorthand property. Expansion is purely syntactic: it routes the value's component values to its longhands
 * (omitted longhands get {@code initial}), and each longhand then parses its part like a declared value. That keeps
 * one parser per longhand, and lets {@code var()} in shorthands re-expand after substitution.
 *
 * @param joiner   serialises longhand values (in {@link #longhands} order) as this shorthand's value, or returns
 *                 null when they cannot be expressed by it
 * @param expander returns the value of every longhand, in {@link #longhands} order, or null if invalid
 */
record Shorthand(String name, List<Longhand> longhands, Function<List<String>, String> joiner,
                 Function<List<ComponentValue>, Map<Longhand, List<ComponentValue>>> expander) {
    Map<Longhand, List<ComponentValue>> expand(List<ComponentValue> value) {
        return expander.apply(value);
    }

    String join(List<String> values) {
        return joiner.apply(values);
    }
}
