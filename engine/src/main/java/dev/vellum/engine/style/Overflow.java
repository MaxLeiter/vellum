package dev.vellum.engine.style;

/** The {@code overflow-x} / {@code overflow-y} properties. */
public enum Overflow {
    VISIBLE, HIDDEN, CLIP, SCROLL, AUTO;

    /** True when content is clipped to the padding box. */
    public boolean clips() { return this != VISIBLE; }
    /** True when the box is a scroll container (it can be scrolled by the wheel or by script). */
    public boolean scrolls() { return this == SCROLL || this == AUTO || this == HIDDEN; }
}
