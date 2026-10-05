package dev.vellum.engine.css;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;

import java.util.List;

/**
 * Selector matching for the DOM API (matches, closest, querySelector, querySelectorAll).
 * STUB: implemented by the CSS workstream.
 */
public final class Selectors {
    private Selectors() {}

    /** @throws IllegalArgumentException for an invalid selector (scripts see a SyntaxError) */
    public static boolean matches(Element element, String selector) {
        throw new UnsupportedOperationException("TODO");
    }

    /** First descendant of {@code scope} (not scope itself) matching, in document order, or null. */
    public static Element querySelector(Node scope, String selector) {
        throw new UnsupportedOperationException("TODO");
    }

    /** All descendants of {@code scope} matching, in document order. */
    public static List<Element> querySelectorAll(Node scope, String selector) {
        throw new UnsupportedOperationException("TODO");
    }
}
