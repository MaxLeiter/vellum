package dev.vellum.engine.paint;

import dev.vellum.engine.style.BorderStyle;
import dev.vellum.engine.style.Colors;

import static dev.vellum.engine.paint.Shapes.BOTTOM;
import static dev.vellum.engine.paint.Shapes.LEFT;
import static dev.vellum.engine.paint.Shapes.RIGHT;
import static dev.vellum.engine.paint.Shapes.TOP;

/**
 * Paints borders and outlines with per-side styles. Solid, inset and outset sides are one ring through
 * {@link Canvas#fillBorder}; groove, ridge and double add a second, inner ring; dashed and dotted sides keep their
 * share of the corners and draw their straight part as segments.
 *
 * <p>Bevels shade the side colour with {@link Colors#shade}: inset darkens the top and left sides and lightens the
 * bottom and right, outset the reverse. {@code border: 2px outset #c6c6c6} gives vanilla's panel bevel: a white
 * highlight and a #555555 shadow.
 */
final class Borders {
    static final float DARK = 0.43f, LIGHT = 1.3f;

    private final float[] widthsA = new float[4], widthsB = new float[4], offsetsB = new float[4];
    private final float[] rectB = new float[4], radiiB = new float[8], side = new float[8];
    private final int[] colorsA = new int[4], colorsB = new int[4];

    /**
     * @param rect   {x, y, width, height} of the outer edge, snapped
     * @param radii  outer corner radii
     * @param widths top, right, bottom, left widths, snapped
     */
    void paint(Canvas canvas, QuadBatch batch, float dp, float[] rect, float[] radii, float[] widths,
               BorderStyle[] styles, int[] colors) {
        int dashed = 0;
        boolean inner = false, any = false;
        for (int i = 0; i < 4; i++) {
            BorderStyle style = styles[i];
            float w = style.isVisible() ? Math.max(0, widths[i]) : 0;
            int c = Colors.isTransparent(colors[i]) || w <= 0 ? 0 : colors[i];
            widthsA[i] = w;
            colorsA[i] = c;
            widthsB[i] = 0;
            offsetsB[i] = w;
            colorsB[i] = 0;
            switch (style) {
                case INSET, OUTSET -> colorsA[i] = bevel(c, i, style == BorderStyle.INSET);
                case GROOVE, RIDGE -> {
                    float half = Math.max(dp, Geometry.snap(w / 2, dp));
                    if (half < w) {
                        widthsA[i] = offsetsB[i] = half;
                        widthsB[i] = w - half;
                    }
                    colorsA[i] = bevel(c, i, style == BorderStyle.GROOVE);
                    colorsB[i] = bevel(c, i, style == BorderStyle.RIDGE);
                }
                case DOUBLE -> {
                    // Two lines and a gap; too thin for three device pixels and it stays solid.
                    float third = Geometry.snap(w / 3, dp);
                    if (third >= dp && 2 * third < w) {
                        widthsA[i] = widthsB[i] = third;
                        offsetsB[i] = w - third;
                        colorsB[i] = c;
                    }
                }
                case DASHED, DOTTED -> {
                    if (c != 0) dashed |= 1 << i;
                }
                default -> {}
            }
            inner |= widthsB[i] > 0 && colorsB[i] != 0;
            any |= c != 0;
        }
        if (!any) return;
        if (dashed == 0) {
            canvas.fillBorder(rect, radii, widthsA, colorsA);
        } else {
            batch.ring(rect[0], rect[1], rect[2], rect[3], radii, widthsA, colorsA, colorsA, Shapes.ALL_SIDES & ~dashed, dp);
            for (int i = 0; i < 4; i++) if ((dashed & (1 << i)) != 0) dashes(batch, i, rect, radii, styles[i] == BorderStyle.DOTTED, dp);
            batch.flush(canvas);
        }
        if (inner) {
            rectB[0] = rect[0] + offsetsB[LEFT];
            rectB[1] = rect[1] + offsetsB[TOP];
            rectB[2] = rect[2] - offsetsB[LEFT] - offsetsB[RIGHT];
            rectB[3] = rect[3] - offsetsB[TOP] - offsetsB[BOTTOM];
            Shapes.insetRadii(radii, offsetsB[TOP], offsetsB[RIGHT], offsetsB[BOTTOM], offsetsB[LEFT], radiiB);
            canvas.fillBorder(rectB, radiiB, widthsB, colorsB);
        }
    }

    /** Inset darkens the top and left sides and lightens the bottom and right; outset the reverse. */
    static int bevel(int color, int side, boolean inset) {
        boolean topLeft = side == TOP || side == LEFT;
        return Colors.shade(color, topLeft == inset ? DARK : LIGHT);
    }

    /**
     * The straight part of a dashed or dotted side as segments: dashes three widths long with equal gaps, dots
     * square, stretched so the side starts and ends with a whole segment. Segments are clipped to the side's
     * trapezoid so square corners keep their diagonal joins.
     */
    private void dashes(QuadBatch batch, int i, float[] rect, float[] radii, boolean dotted, float dp) {
        float w = widthsA[i];
        Shapes.sideQuad(i, rect[0], rect[1], rect[2], rect[3], radii, widthsA, side);
        boolean horizontal = i == TOP || i == BOTTOM;
        float start = horizontal ? Math.min(side[0], side[2]) : Math.min(side[1], side[3]);
        float length = (horizontal ? Math.max(side[0], side[2]) : Math.max(side[1], side[3])) - start;
        if (!(length > 0)) return;
        float dash = dotted ? w : 3 * w, gap = dash;
        int n = Math.max(1, Math.round((length + gap) / (dash + gap)));
        float scale = length / (n * dash + (n - 1) * gap);
        dash *= scale;
        gap *= scale;
        float across = switch (i) {
            case TOP -> rect[1];
            case BOTTOM -> rect[1] + rect[3] - w;
            case LEFT -> rect[0];
            default -> rect[0] + rect[2] - w;
        };
        batch.setClip(side, 4);
        for (int k = 0; k < n; k++) {
            float p0 = Geometry.snap(start + k * (dash + gap), dp), p1 = Geometry.snap(start + k * (dash + gap) + dash, dp);
            if (horizontal) batch.rect(p0, across, p1 - p0, w, colorsA[i]);
            else batch.rect(across, p0, w, p1 - p0, colorsA[i]);
        }
        batch.clearClip();
    }
}
