package dev.vellum.engine.dom;

/** What a style or a selector is for: an element itself, or one of its pseudo-elements. */
public enum PseudoElement {
    NONE(""), BEFORE("::before"), AFTER("::after"), PLACEHOLDER("::placeholder");

    /** The CSS name, as events report it ({@code ""} for the element itself). */
    public final String cssName;

    PseudoElement(String cssName) {
        this.cssName = cssName;
    }
}
