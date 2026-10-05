package dev.vellum.engine.css;

import dev.vellum.engine.dom.Element;

/**
 * Read/write access to an element's inline {@code style} attribute, for the script binding of
 * {@code element.style} (CSSStyleDeclaration). Property names are CSS names ({@code background-color}, or
 * {@code --custom}); the script layer converts camelCase. Every write rewrites the {@code style} attribute, which
 * invalidates style. STUB: implemented by the CSS workstream.
 */
public final class InlineStyle {
    private InlineStyle() {}

    /** The declared value text, or "" if not set. Shorthands are reconstructed when all longhands are set. */
    public static String getPropertyValue(Element element, String property) { return ""; }

    /** "important" or "". */
    public static String getPropertyPriority(Element element, String property) { return ""; }

    /** Sets a declaration; a null or empty value removes it. Invalid values are ignored, as in browsers. */
    public static void setProperty(Element element, String property, String value, String priority) {}

    /** Removes a declaration and returns its old value ("" if none). */
    public static String removeProperty(Element element, String property) { return ""; }

    /** The serialised declarations. */
    public static String cssText(Element element) { return ""; }

    public static void setCssText(Element element, String cssText) {}

    /** Number of declared properties (after shorthand expansion). */
    public static int length(Element element) { return 0; }

    /** The name of the index-th declared property, or "". */
    public static String item(Element element, int index) { return ""; }
}
