package dev.vellum.engine.css;

import java.util.List;

/**
 * A CSS component value (CSS Syntax 3 §5): a preserved {@link Token}, a function with its arguments, or a simple
 * block. Every component value remembers the source range it was parsed from, so values can be serialised exactly
 * as written (inline styles, custom properties, sprite ids).
 */
sealed interface ComponentValue permits Token, ComponentValue.Func, ComponentValue.Block {
    String source();

    int start();

    int end();

    int line();

    /** The source text of this value. */
    default String text() {
        return source().substring(start(), end());
    }

    /**
     * The source text of a run of values, trimmed. Whitespace between neighbours from the same source is kept as
     * written; values that were not adjacent in one source (shorthand expansion picks and fills in values) are
     * joined with a single space.
     */
    static String text(List<ComponentValue> values) {
        StringBuilder sb = new StringBuilder();
        ComponentValue prev = null;
        for (ComponentValue v : values) {
            if (v instanceof Token t && t.is(Token.Type.WHITESPACE)) continue;
            if (prev != null) {
                String gap = v.source() == prev.source() && prev.end() <= v.start()
                        ? v.source().substring(prev.end(), v.start()) : null;
                if (gap != null && gap.isBlank()) sb.append(gap);
                else if (!(v instanceof Token t && t.is(Token.Type.COMMA))) sb.append(' ');
            }
            sb.append(v.text());
            prev = v;
        }
        return sb.toString();
    }

    /** A function such as {@code rgb(...)}; {@code name} is lower-case, {@code args} exclude the parentheses. */
    record Func(String name, List<ComponentValue> args, String source, int start, int end, int line)
            implements ComponentValue {}

    /** A {@code (...)}, {@code [...]} or <code>{...}</code> block; {@code open} is the opening character. */
    record Block(char open, List<ComponentValue> body, String source, int start, int end, int line)
            implements ComponentValue {}
}
