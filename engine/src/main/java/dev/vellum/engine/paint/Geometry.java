package dev.vellum.engine.paint;

import dev.vellum.engine.layout.Box;
import dev.vellum.engine.layout.Fragment;
import dev.vellum.engine.style.BackgroundLayer;
import dev.vellum.engine.style.ComputedStyle;

/**
 * The painted geometry of a box or inline fragment: border box, border and padding widths, corner radii. Every
 * edge is rounded to the device pixel grid independently, so borders stay crisp at any GUI scale and adjacent boxes
 * still meet. A reused scratch object.
 */
final class Geometry {
    float x, y, width, height;
    /** Top, right, bottom, left. */
    final float[] border = new float[4], padding = new float[4];
    final float[] radii = new float[8];
    boolean rounded;

    /** A box's geometry with its border box at (x, y). */
    Geometry box(Box b, ComputedStyle s, float x, float y, float devicePixel) {
        return set(x, y, b.width, b.height, b.borderTop, b.borderRight, b.borderBottom, b.borderLeft,
                b.paddingTop, b.paddingRight, b.paddingBottom, b.paddingLeft, s, devicePixel);
    }

    /**
     * An inline box fragment, whose rectangle is taken to be its border box. The start and end edges (left and
     * right border, padding and corners) only exist on the lines where the element starts and ends.
     */
    Geometry fragment(Fragment.InlineBox f, ComputedStyle s, float x, float y, float devicePixel) {
        boolean first = f.first(), last = f.last();
        set(x + f.x(), y + f.y(), f.width(), f.height(),
                s.borderTopWidth, last ? s.borderRightWidth : 0, s.borderBottomWidth, first ? s.borderLeftWidth : 0,
                s.paddingTop.resolve(0), last ? s.paddingRight.resolve(0) : 0, s.paddingBottom.resolve(0),
                first ? s.paddingLeft.resolve(0) : 0, s, devicePixel);
        if (!first) radii[0] = radii[1] = radii[6] = radii[7] = 0;
        if (!last) radii[2] = radii[3] = radii[4] = radii[5] = 0;
        rounded = Shapes.isRounded(radii);
        return this;
    }

    private Geometry set(float x, float y, float w, float h, float bt, float br, float bb, float bl,
                         float pt, float pr, float pb, float pl, ComputedStyle s, float dp) {
        float x0 = snap(x, dp), y0 = snap(y, dp);
        this.x = x0;
        this.y = y0;
        width = snap(x + w, dp) - x0;
        height = snap(y + h, dp) - y0;
        border[0] = edge(y, bt, y0, dp);
        border[1] = edge(-(x + w), br, -(x0 + width), dp);
        border[2] = edge(-(y + h), bb, -(y0 + height), dp);
        border[3] = edge(x, bl, x0, dp);
        padding[0] = edge(y + bt, pt, y0 + border[0], dp);
        padding[1] = edge(-(x + w - br), pr, -(x0 + width - border[1]), dp);
        padding[2] = edge(-(y + h - bb), pb, -(y0 + height - border[2]), dp);
        padding[3] = edge(x + bl, pl, x0 + border[3], dp);
        rounded = Shapes.radii(s, width, height, radii);
        return this;
    }

    /** Snapped width of an edge band from {@code pos} (snapped: {@code snappedPos}); a non-zero band keeps at least one device pixel. */
    private static float edge(float pos, float width, float snappedPos, float dp) {
        if (!(width > 0)) return 0;
        return Math.max(dp, snap(pos + width, dp) - snappedPos);
    }

    static float snap(float v, float devicePixel) {
        return Math.round(v / devicePixel) * devicePixel;
    }

    /** Writes the {x, y, width, height} and radii of the border, padding or content box. */
    void area(BackgroundLayer.Box which, float[] rect, float[] outRadii) {
        float t = 0, r = 0, b = 0, l = 0;
        if (which != BackgroundLayer.Box.BORDER_BOX) {
            t = border[0];
            r = border[1];
            b = border[2];
            l = border[3];
        }
        if (which == BackgroundLayer.Box.CONTENT_BOX) {
            t += padding[0];
            r += padding[1];
            b += padding[2];
            l += padding[3];
        }
        rect[0] = x + l;
        rect[1] = y + t;
        rect[2] = Math.max(0, width - l - r);
        rect[3] = Math.max(0, height - t - b);
        Shapes.insetRadii(radii, t, r, b, l, outRadii);
    }
}
