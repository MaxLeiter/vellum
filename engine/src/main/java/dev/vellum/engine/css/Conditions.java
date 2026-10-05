package dev.vellum.engine.css;

import dev.vellum.engine.css.ComponentValue.Block;
import dev.vellum.engine.css.Token.Type;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Boolean conditions shared by {@code @media} and {@code @supports}:
 * {@code not C | C [and C]* | C [or C]*} where each {@code C} is a parenthesised condition or a leaf
 * (a media feature, a declaration, {@code selector()}), which the caller evaluates.
 */
final class Conditions {
    private Conditions() {}

    /**
     * Parses a condition. {@code leaf} turns one parenthesised block or function that is not a nested condition
     * into a predicate (or null when it is malformed). Returns null when the whole condition is malformed.
     */
    static <T> Predicate<T> parse(List<ComponentValue> values, Function<ComponentValue, Predicate<T>> leaf) {
        List<ComponentValue> items = ValueReader.items(values);
        if (items.isEmpty()) return null;
        if (items.get(0) instanceof Token t && t.isIdent("not")) {
            Predicate<T> inner = items.size() == 2 ? term(items.get(1), leaf) : null;
            return inner == null ? null : inner.negate();
        }
        Predicate<T> result = term(items.get(0), leaf);
        String joiner = null;
        for (int i = 1; i < items.size() && result != null; i += 2) {
            String op = items.get(i) instanceof Token t && t.is(Type.IDENT) ? t.lower : "";
            if (!(op.equals("and") || op.equals("or")) || (joiner != null && !joiner.equals(op)) || i + 1 >= items.size()) {
                return null;
            }
            joiner = op;
            Predicate<T> next = term(items.get(i + 1), leaf);
            if (next == null) return null;
            result = op.equals("and") ? result.and(next) : result.or(next);
        }
        return result;
    }

    /** A parenthesised nested condition, or a leaf. */
    private static <T> Predicate<T> term(ComponentValue v, Function<ComponentValue, Predicate<T>> leaf) {
        if (v instanceof Block b && b.open() == '(') {
            List<ComponentValue> body = ValueReader.items(b.body());
            boolean nested = !body.isEmpty() && (body.get(0) instanceof Block
                    || (body.get(0) instanceof Token t && t.isIdent("not") && body.size() == 2));
            if (nested) return parse(b.body(), leaf);
        }
        return leaf.apply(v);
    }
}
