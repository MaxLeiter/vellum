package dev.vellum.engine.style;

/**
 * A grid placement value ({@code grid-column-start} etc.): {@code auto}, a line number (negative counts from the
 * end), {@code span n}, or a named line / area.
 */
public record GridLine(Kind kind, int value, String name) {
    public enum Kind { AUTO, LINE, SPAN, NAMED }

    public static final GridLine AUTO = new GridLine(Kind.AUTO, 0, null);

    public static GridLine line(int n) { return new GridLine(Kind.LINE, n, null); }
    public static GridLine span(int n) { return new GridLine(Kind.SPAN, Math.max(1, n), null); }
    public static GridLine named(String name) { return new GridLine(Kind.NAMED, 1, name); }

    public boolean isAuto() { return kind == Kind.AUTO; }
}
