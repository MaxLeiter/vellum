package dev.vellum.engine.input;

import dev.vellum.engine.layout.Box;
import dev.vellum.engine.paint.Canvas;

/**
 * Painting of built-in form controls (text in inputs and textareas with caret and selection, checkbox ticks, range
 * tracks and thumbs, select labels and arrows, progress/meter fills, placeholders). The painter calls
 * {@link #paint} after a control's background and border, inside its transform/opacity. STUB: implemented by the
 * input workstream.
 */
public final class Controls {
    private Controls() {}

    /** True for elements whose content is drawn by {@link #paint} rather than laid out from children. */
    public static boolean isControl(String tag) {
        return switch (tag) {
            case "input", "textarea", "select", "progress", "meter" -> true;
            default -> false;
        };
    }

    /** Paints the control's content into {@code box} (coordinates: the box's border-box origin is at 0,0). */
    public static void paint(Canvas canvas, Box box) {
    }
}
