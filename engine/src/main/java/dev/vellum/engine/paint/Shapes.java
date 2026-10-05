package dev.vellum.engine.paint;

/** Tessellation of shapes into quads for {@link Canvas}. STUB: implemented by the paint workstream. */
public final class Shapes {
    private Shapes() {}

    public static void fillRoundedRect(Canvas canvas, float x, float y, float width, float height, float[] radii, int argb) {
        canvas.fillRect(x, y, width, height, argb);
    }

    public static void fillBorder(Canvas canvas, float[] outer, float[] radii, float[] widths, int[] colors) {
        float x = outer[0], y = outer[1], w = outer[2], h = outer[3];
        canvas.fillRect(x, y, w, widths[0], colors[0]);
        canvas.fillRect(x + w - widths[1], y + widths[0], widths[1], h - widths[0] - widths[2], colors[1]);
        canvas.fillRect(x, y + h - widths[2], w, widths[2], colors[2]);
        canvas.fillRect(x, y + widths[0], widths[3], h - widths[0] - widths[2], colors[3]);
    }
}
