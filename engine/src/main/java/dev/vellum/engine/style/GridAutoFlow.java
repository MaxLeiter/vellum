package dev.vellum.engine.style;

/** The {@code grid-auto-flow} property. */
public enum GridAutoFlow {
    ROW, COLUMN, ROW_DENSE, COLUMN_DENSE;

    public boolean isColumn() { return this == COLUMN || this == COLUMN_DENSE; }
    public boolean isDense() { return this == ROW_DENSE || this == COLUMN_DENSE; }
}
