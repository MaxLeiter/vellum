package dev.vellum.engine.style;

/** The {@code overflow-x} / {@code overflow-y} properties. */
public enum Overflow {
    VISIBLE, HIDDEN, CLIP, SCROLL, AUTO;

    /** True when content is clipped to the padding box. */
    public boolean clips() { return this != VISIBLE; }
    /** True when the box is a scroll container (it can be scrolled by the wheel or by script). */
    public boolean scrolls() { return this == SCROLL || this == AUTO || this == HIDDEN; }
    /** True when the user can scroll the box (wheel, scrollbar); {@code hidden} only scrolls by script. */
    public boolean userScrollable() { return this == SCROLL || this == AUTO; }
}
