package dev.vellum.engine.css;

import dev.vellum.engine.css.ComponentValue.Func;
import dev.vellum.engine.css.Token.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A cursor over component values for value parsers. Whitespace is skipped. Parsers return null for invalid input;
 * callers that try alternatives use {@link #mark()} / {@link #reset(int)}.
 */
final class ValueReader {
    private final List<ComponentValue> values;
    private int pos;

    ValueReader(List<ComponentValue> values) {
        this.values = values;
    }

    private void skipWhitespace() {
        while (pos < values.size() && values.get(pos) instanceof Token t && t.is(Type.WHITESPACE)) pos++;
    }

    boolean atEnd() {
        skipWhitespace();
        return pos >= values.size();
    }

    ComponentValue peek() {
        skipWhitespace();
        return pos < values.size() ? values.get(pos) : null;
    }

    ComponentValue next() {
        ComponentValue v = peek();
        if (v != null) pos++;
        return v;
    }

    /** The next value if it is a token of {@code type} (consumed), else null. */
    Token next(Type type) {
        if (peek() instanceof Token t && t.is(type)) {
            pos++;
            return t;
        }
        return null;
    }

    /** The next value if it is a function named {@code name} (consumed), else null. */
    Func function(String name) {
        if (peek() instanceof Func f && f.name().equals(name)) {
            pos++;
            return f;
        }
        return null;
    }

    /** The next ident in lower case without consuming it, or null. */
    String peekIdent() {
        return peek() instanceof Token t && t.is(Type.IDENT) ? t.lower : null;
    }

    /** Consumes the next value if it is the ident {@code lowerName}. */
    boolean ident(String lowerName) {
        if (lowerName.equals(peekIdent())) {
            pos++;
            return true;
        }
        return false;
    }

    boolean comma() {
        return next(Type.COMMA) != null;
    }

    boolean delim(char c) {
        if (peek() instanceof Token t && t.isDelim(c)) {
            pos++;
            return true;
        }
        return false;
    }

    int mark() { return pos; }

    void reset(int mark) { pos = mark; }

    /** Values from {@code mark} up to the current position. */
    List<ComponentValue> since(int mark) {
        return CssParser.trim(values.subList(mark, pos));
    }

    /** Runs {@code parser}; returns the values it consumed, or null (consuming nothing) when it fails. */
    List<ComponentValue> take(Longhand.Parser parser, ValueContext ctx) {
        int m = pos;
        if (parser.parse(this, ctx) != null && pos > m) return since(m);
        pos = m;
        return null;
    }

    /** The remaining values, consumed. */
    List<ComponentValue> rest() {
        List<ComponentValue> r = CssParser.trim(values.subList(pos, values.size()));
        pos = values.size();
        return r;
    }

    /** Splits values on top-level commas. */
    static List<List<ComponentValue>> splitCommas(List<ComponentValue> values) {
        return split(values, v -> v instanceof Token t && t.is(Type.COMMA));
    }

    /** Splits values on top-level slashes ({@code grid-area: a / b}). */
    static List<List<ComponentValue>> splitSlashes(List<ComponentValue> values) {
        return split(values, v -> v instanceof Token t && t.isDelim('/'));
    }

    private static List<List<ComponentValue>> split(List<ComponentValue> values, Predicate<ComponentValue> separator) {
        List<List<ComponentValue>> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= values.size(); i++) {
            if (i == values.size() || separator.test(values.get(i))) {
                out.add(CssParser.trim(values.subList(start, i)));
                start = i + 1;
            }
        }
        return out;
    }

    /** The values without whitespace: the space-separated items of a value. */
    static List<ComponentValue> items(List<ComponentValue> values) {
        List<ComponentValue> out = new ArrayList<>(values.size());
        for (ComponentValue v : values) if (!(v instanceof Token t && t.is(Type.WHITESPACE))) out.add(v);
        return out;
    }
}
