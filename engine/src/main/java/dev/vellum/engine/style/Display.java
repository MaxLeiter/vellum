package dev.vellum.engine.style;

/** The {@code display} property. {@code CONTENTS} removes the element's own box but keeps its children. */
public enum Display {
    NONE, BLOCK, INLINE, INLINE_BLOCK, FLEX, INLINE_FLEX, GRID, INLINE_GRID, CONTENTS;

    public boolean isInlineLevel() {
        return this == INLINE || this == INLINE_BLOCK || this == INLINE_FLEX || this == INLINE_GRID;
    }

    public boolean isFlex() { return this == FLEX || this == INLINE_FLEX; }
    public boolean isGrid() { return this == GRID || this == INLINE_GRID; }

    /** The outer display type when the element is blockified (flex/grid items, absolutely positioned boxes). */
    public Display blockified() {
        return switch (this) {
            case INLINE, INLINE_BLOCK -> BLOCK;
            case INLINE_FLEX -> FLEX;
            case INLINE_GRID -> GRID;
            default -> this;
        };
    }
}
