package dev.vellum.engine.script;

import dev.vellum.engine.dom.Node;
import dev.vellum.shadow.rhino.Callable;
import dev.vellum.shadow.rhino.Undefined;

import java.util.Arrays;

/** The arguments of a host function call, read with JS coercions. Missing arguments are {@code undefined}. */
record Args(Object[] values) {
    int length() { return values.length; }

    Object get(int i) {
        return i < values.length ? values[i] : Undefined.instance;
    }

    /** Whether argument {@code i} was passed and is not {@code undefined}. */
    boolean has(int i) {
        return !Undefined.isUndefined(get(i));
    }

    boolean isNullish(int i) {
        return Js.isNullish(get(i));
    }

    String str(int i) {
        return Js.str(get(i));
    }

    String str(int i, String fallback) {
        return has(i) ? str(i) : fallback;
    }

    double num(int i, double fallback) {
        return has(i) ? Js.num(get(i)) : fallback;
    }

    boolean bool(int i) {
        return Js.bool(get(i));
    }

    Node node(int i) {
        return Js.unwrap(get(i), Node.class);
    }

    /** A node, or null for null/undefined. */
    Node nodeOrNull(int i) {
        return isNullish(i) ? null : node(i);
    }

    Callable fn(int i) {
        return Js.function(get(i));
    }

    /** The arguments from {@code i} on (for rest parameters such as setTimeout's extra arguments). */
    Object[] from(int i) {
        return i >= values.length ? new Object[0] : Arrays.copyOfRange(values, i, values.length);
    }
}
