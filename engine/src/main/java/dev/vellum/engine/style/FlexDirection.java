package dev.vellum.engine.style;

/** The {@code flex-direction} property. */
public enum FlexDirection {
    ROW, ROW_REVERSE, COLUMN, COLUMN_REVERSE;

    public boolean isRow() { return this == ROW || this == ROW_REVERSE; }
    public boolean isReverse() { return this == ROW_REVERSE || this == COLUMN_REVERSE; }
}
