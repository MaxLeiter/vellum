package dev.vellum.engine.style;

/** The {@code border-*-style} properties. {@code INSET}/{@code OUTSET} draw the classic bevel, which is how vanilla Minecraft panels and slots look. */
public enum BorderStyle {
    NONE, HIDDEN, SOLID, DASHED, DOTTED, DOUBLE, INSET, OUTSET, GROOVE, RIDGE;

    public boolean isVisible() { return this != NONE && this != HIDDEN; }
}
