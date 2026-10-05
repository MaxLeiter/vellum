package dev.vellum.engine.style;

/** The {@code position} property. Sticky is treated as relative. */
public enum Position {
    STATIC, RELATIVE, ABSOLUTE, FIXED, STICKY;

    public boolean isPositioned() { return this != STATIC; }
    public boolean isOutOfFlow() { return this == ABSOLUTE || this == FIXED; }
}
