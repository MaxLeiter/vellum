package dev.vellum.engine.css;

import dev.vellum.engine.css.ComponentValue.Func;
import dev.vellum.engine.css.Token.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a nested style rule's selector (CSS Nesting) against its parent's: {@code &} stands for the parent, and a
 * selector without one is relative to it ({@code .b} is {@code & .b}, {@code > .b} is {@code & > .b}).
 */
final class Nesting {
    private Nesting() {}

    /** {@code prelude} with every {@code &} replaced by {@code parent}, a resolved selector list. */
    static List<ComponentValue> resolve(List<ComponentValue> prelude, List<ComponentValue> parent) {
        boolean compound = isOneCompound(parent);
        List<ComponentValue> out = new ArrayList<>();
        int line = prelude.isEmpty() ? 0 : prelude.get(0).line();
        for (List<ComponentValue> part : ValueReader.splitCommas(prelude)) {
            if (!out.isEmpty()) out.add(synthetic(Type.COMMA, "", ",", line));
            if (!containsNesting(part)) {
                out.addAll(compound ? parent : is(parent, line));
                out.add(synthetic(Type.WHITESPACE, "", " ", line));
            }
            replace(part, parent, compound, out);
        }
        return out;
    }

    private static void replace(List<ComponentValue> values, List<ComponentValue> parent, boolean compound,
                                List<ComponentValue> out) {
        ComponentValue prev = null;
        for (ComponentValue v : values) {
            if (isNesting(v)) {
                // Splicing the parent in is only the same selector where & starts a compound: "div&" is not "divdiv".
                boolean startsCompound = prev == null || prev instanceof Token t && (t.is(Type.WHITESPACE)
                        || t.isDelim('>') || t.isDelim('+') || t.isDelim('~'));
                out.addAll(compound && startsCompound ? parent : is(parent, v.line()));
            } else if (v instanceof Func f && containsNesting(f.args())) {
                List<ComponentValue> args = new ArrayList<>();
                replace(f.args(), parent, compound, args);
                String text = f.name() + "(" + ComponentValue.text(args) + ")";
                out.add(new Func(f.name(), args, text, 0, text.length(), f.line()));
            } else {
                out.add(v);
            }
            prev = v;
        }
    }

    /** {@code :is(parent)}, which keeps the parent's specificity and matches what it matches. */
    private static List<ComponentValue> is(List<ComponentValue> parent, int line) {
        String text = "is(" + ComponentValue.text(parent) + ")";
        return List.of(synthetic(Type.COLON, "", ":", line), new Func("is", parent, text, 0, text.length(), line));
    }

    /** Whether {@code selector} is a single compound selector: no list, combinator or pseudo-element. */
    private static boolean isOneCompound(List<ComponentValue> selector) {
        ComponentValue prev = null;
        for (ComponentValue v : selector) {
            if (v instanceof Token t && (t.is(Type.WHITESPACE) || t.is(Type.COMMA) || t.isDelim('>')
                    || t.isDelim('+') || t.isDelim('~') || t.is(Type.COLON) && prev instanceof Token p
                    && p.is(Type.COLON))) {
                return false;
            }
            prev = v;
        }
        return !selector.isEmpty();
    }

    private static boolean containsNesting(List<ComponentValue> values) {
        for (ComponentValue v : values) {
            if (isNesting(v) || v instanceof Func f && containsNesting(f.args())) return true;
        }
        return false;
    }

    private static boolean isNesting(ComponentValue v) {
        return v instanceof Token t && t.isDelim('&');
    }

    private static Token synthetic(Type type, String value, String text, int line) {
        return new Token(type, value, 0, false, text, 0, text.length(), line);
    }
}
