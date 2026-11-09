package dev.vellum.engine.paint;

import dev.vellum.engine.layout.Box;
import dev.vellum.engine.layout.Fragment;
import dev.vellum.engine.style.BackgroundLayer;
import dev.vellum.engine.style.ComputedStyle;

/**
 * The painted geometry of a box or inline fragment: border box, border and padding widths, corner radii, every edge
 * on the device pixel grid by {@link PixelGrid}'s rules, so borders stay crisp at any GUI scale and adjacent boxes
 * still meet. A reused scratch object.
 */
final class Geometry {
    float x, y, width, height;
    /** Top, right, bottom, left. */
    final float[] border = new float[4], padding = new float[4];
    final float[] radii = new float[8];
    boolean rounded;
    private final float[] scratch = new float[4];

    /** A box's geometry with its border box at (x, y). */
    Geometry box(Box b, ComputedStyle s, float x, float y, float devicePixel) {
        return set(x, y, b.width, b.height, b.borderTop, b.borderRight, b.borderBottom, b.borderLeft,
                b.paddingTop, b.paddingRight, b.paddingBottom, b.paddingLeft, s, devicePixel);
    }

    /**
     * An inline box fragment, whose rectangle is taken to be its border box, with the edges layout resolved for its
     * box. The start and end edges (left and right border, padding and corners) only exist on the lines where the
     * element starts and ends.
     */
    Geometry fragment(Fragment.InlineBox f, ComputedStyle s, float x, float y, float devicePixel) {
        Box b = f.box();
        boolean first = f.first(), last = f.last();
        set(x + f.x(), y + f.y(), f.width(), f.height(),
                b.borderTop, last ? b.borderRight : 0, b.borderBottom, first ? b.borderLeft : 0,
                b.paddingTop, last ? b.paddingRight : 0, b.paddingBottom, first ? b.paddingLeft : 0, s, devicePixel);
        if (!first) radii[0] = radii[1] = radii[6] = radii[7] = 0;
        if (!last) radii[2] = radii[3] = radii[4] = radii[5] = 0;
        rounded = Shapes.isRounded(radii);
        return this;
    }

    /** Snaps the border box's edges, then each border and padding band from its snapped outer edge to its inner one. */
    private Geometry set(float x, float y, float w, float h, float bt, float br, float bb, float bl,
                         float pt, float pr, float pb, float pl, ComputedStyle s, float dp) {
        float[] box = PixelGrid.rect(x, y, w, h, dp, scratch);
        this.x = box[0];
        this.y = box[1];
        width = box[2];
        height = box[3];
        float x0 = this.x, y0 = this.y, x1 = x0 + width, y1 = y0 + height;
        border[0] = PixelGrid.band(y0, y + bt, bt, dp);
        border[1] = PixelGrid.band(x1, x + w - br, br, dp);
        border[2] = PixelGrid.band(y1, y + h - bb, bb, dp);
        border[3] = PixelGrid.band(x0, x + bl, bl, dp);
        padding[0] = PixelGrid.band(y0 + border[0], y + bt + pt, pt, dp);
        padding[1] = PixelGrid.band(x1 - border[1], x + w - br - pr, pr, dp);
        padding[2] = PixelGrid.band(y1 - border[2], y + h - bb - pb, pb, dp);
        padding[3] = PixelGrid.band(x0 + border[3], x + bl + pl, pl, dp);
        rounded = Shapes.radii(s, width, height, radii);
        return this;
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
