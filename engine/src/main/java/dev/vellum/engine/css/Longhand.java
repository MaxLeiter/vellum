package dev.vellum.engine.css;

import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Prop;

import java.util.function.Function;

/**
 * A CSS longhand as the cascade sees it: its value parser and serialiser, and where the computed value goes. Most
 * longhands set one {@link Prop}; the {@code background-*}, {@code transition-*} and {@code animation-*} longhands
 * are components of a {@link ListGroup} instead. The registry is {@link Properties}.
 */
final class Longhand {
    /**
     * Parses and computes a value. Returns null when the input is invalid; must consume what it accepts (callers
     * require the whole value to be consumed).
     */
    @FunctionalInterface
    interface Parser {
        Object parse(ValueReader r, ValueContext ctx);
    }

    /** A parsed value meaning "null" in {@link ComputedStyle}: {@code z-index: auto}, {@code content: none}... */
    enum None { VALUE }

    /** {@code line-height} as a unitless factor: what inherits, so descendants multiply their own font size. */
    record LineHeightFactor(float factor) {}

    final int id;
    final String name;
    /** The property this longhand sets, or null for list components. */
    final Prop prop;
    /** For list components: the group and the component index; otherwise null. */
    final ListGroup group;
    final int component;
    final Parser parser;
    private final Function<Object, String> serializer;
    /**
     * The CSS initial value when it differs from {@link ComputedStyle}'s default (border widths are {@code medium},
     * border and outline colours are {@code currentColor}), applied by the cascade; otherwise null.
     */
    Decl initial;

    Longhand(int id, String name, Prop prop, ListGroup group, int component, Parser parser,
             Function<Object, String> serializer) {
        this.id = id;
        this.name = name;
        this.prop = prop;
        this.group = group;
        this.component = component;
        this.parser = parser;
        this.serializer = serializer;
    }

    boolean inherited() {
        return prop != null && prop.inherited;
    }

    /**
     * The computed value of this longhand in {@code s}, in the form its parser produces (for list components, the
     * component's list).
     */
    Object get(ComputedStyle s) {
        if (group != null) return group.decompose((java.util.List<?>) group.prop.get(s)).get(component);
        if (prop == Prop.LINE_HEIGHT && !Float.isNaN(s.lineHeightFactor)) return new LineHeightFactor(s.lineHeightFactor);
        Object v = prop.get(s);
        return v == null ? None.VALUE : v;
    }

    /** Stores a computed value. Line heights given as a factor resolve against the font size already computed. */
    void set(ComputedStyle s, Object value) {
        if (prop == Prop.LINE_HEIGHT) {
            s.lineHeightFactor = value instanceof LineHeightFactor f ? f.factor() : Float.NaN;
            s.lineHeight = value instanceof LineHeightFactor f ? f.factor() * s.fontSize : (Float) value;
        } else {
            prop.set(s, value == None.VALUE ? null : value);
        }
    }

    /** Serialises a computed value of this longhand (a component list for list components). */
    String serialize(Object value) {
        return serializer.apply(value);
    }

    @Override
    public String toString() {
        return name;
    }
}
