package dev.vellum.engine.paint;

import dev.vellum.engine.style.Shadow;

/**
 * A rounded box, border or box-shadow described for drawing with smooth edges: as one quad whose every pixel works out
 * how much of it the shape covers from the shape's signed distance. Minecraft 26.x draws these with a shader
 * ({@code assets/vellum/shaders/core/sdf_box}); the previewer runs {@link #coverage} on the CPU, so the two match.
 *
 * <p>The factories return null for what only the tessellation draws (corners that are not circular, borders that
 * differ by side, shadows whose corners differ, offset inset shadows), and {@link #NOTHING} for a shape that draws
 * nothing at all (no size left, or spread to nothing).
 *
 * @param x0     the quad, in the canvas's coordinates: the shape's bounds, grown by the blur. A backend adds a device
 *               pixel on every side for the antialiased edge.
 * @param cx     the centre of the box ({@code coverage} takes positions relative to it), moved by the offset for an
 *               outer shadow
 * @param radii  the box's corner radii, as in {@link Canvas#fillRoundedRect} (x and y equal)
 * @param a      a box's border width (0 fills it); a shadow's sigma
 * @param b      a shadow's spread
 * @param cutout an outer shadow that is not offset: the box itself stays uncovered, so translucent boxes don't darken
 */
public record SmoothShape(boolean shadow, float x0, float y0, float x1, float y1, float cx, float cy, float halfWidth,
                          float halfHeight, float[] radii, float a, float b, boolean inset, boolean cutout, int argb) {
    /** A shape that draws nothing. */
    public static final SmoothShape NOTHING = new SmoothShape(false, 0, 0, 0, 0, 0, 0, 0, 0, new float[8], 0, 0, false, false, 0);

    /** A rounded box, filled ({@code border} 0) or its border of that width; null to tessellate it. */
    public static SmoothShape box(float x, float y, float width, float height, float[] radii, float border, int argb) {
        if (!Shapes.isRounded(radii) || !circular(radii)) return null;
        if (width <= 0 || height <= 0) return NOTHING;
        float hw = width / 2, hh = height / 2;
        return new SmoothShape(false, x, y, x + width, y + height, x + hw, y + hh, hw, hh, radii, border, 0, false, false, argb);
    }

    /** A rounded border of one width and colour (see {@link Canvas#fillBorder}); null to tessellate it. */
    public static SmoothShape border(float[] outer, float[] radii, float[] widths, int[] colors) {
        boolean uniform = widths[0] > 0 && colors[0] == colors[1] && colors[0] == colors[2] && colors[0] == colors[3]
                && widths[0] == widths[1] && widths[0] == widths[2] && widths[0] == widths[3];
        return uniform ? box(outer[0], outer[1], outer[2], outer[3], radii, widths[0], colors[0]) : null;
    }

    /** A box-shadow (see {@link Canvas#fillBoxShadow}); null to tessellate it. */
    public static SmoothShape shadow(float[] box, float[] radii, Shadow s) {
        boolean offset = s.offsetX() != 0 || s.offsetY() != 0;
        float blur = Math.max(0, s.blur());
        // Square, sharp shadows stay exact quads; the blur works with one corner radius.
        if ((blur == 0 && !Shapes.isRounded(radii)) || (s.inset() && offset) || !oneRadius(radii)) return null;
        float sigma = blur / 2, spread = s.spread(), hw = box[2] / 2, hh = box[3] / 2;
        if (s.inset()) {
            if (box[2] <= 0 || box[3] <= 0) return NOTHING;
            return new SmoothShape(true, box[0], box[1], box[0] + box[2], box[1] + box[3], box[0] + hw, box[1] + hh, hw, hh,
                    radii, sigma, spread, true, false, s.color());
        }
        if (hw + spread <= 0 || hh + spread <= 0) return NOTHING; // spread to nothing
        // The Gaussian reaches 3 sigma past the shape before it fades out.
        float cx = box[0] + hw + s.offsetX(), cy = box[1] + hh + s.offsetY();
        float ex = hw + spread + 3 * sigma, ey = hh + spread + 3 * sigma;
        return new SmoothShape(true, cx - ex, cy - ey, cx + ex, cy + ey, cx, cy, hw, hh, radii, sigma, spread, false, !offset,
                s.color());
    }

    /**
     * How much of the pixel at ({@code x}, {@code y}), relative to ({@link #cx}, {@link #cy}), the shape covers, from 0
     * to 1: what {@code sdf_box.fsh} computes. {@code aa} is the size of a device pixel in the shape's units.
     */
    public float coverage(float x, float y, float aa) {
        float r = cornerRadius(x, y, radii);
        if (!shadow) {
            float box = coverage(roundedBox(x, y, halfWidth, halfHeight, r), aa);
            if (a <= 0) return box;
            float iw = halfWidth - a, ih = halfHeight - a;
            if (iw <= 0 || ih <= 0) return box;
            return box * (1 - coverage(roundedBox(x, y, iw, ih, Shapes.inset(r, a)), aa));
        }
        // An outer shadow that is not a cutout ignores the box; a cutout's inside needs no blur.
        float box = inset || cutout ? coverage(roundedBox(x, y, halfWidth, halfHeight, r), aa) : 0;
        if (cutout && !inset && box >= 1) return 0;
        float sigma = Math.max(a, aa * 0.5f), spread = inset ? -b : b;
        float sw = halfWidth + spread, sh = halfHeight + spread;
        float corner = Math.min(Shapes.inset(r, -spread), Math.min(sw, sh));
        float blurred = sw > 0 && sh > 0 ? blurredBox(x, y, sw, sh, corner, sigma) : 0;
        return inset ? box * (1 - blurred) : blurred * (1 - box);
    }

    /** The radius of the corner whose quadrant (x, y) is in (y down). */
    private static float cornerRadius(float x, float y, float[] r) {
        boolean right = !(x < 0), bottom = !(y < 0);
        int k = 0;
        while (Shapes.isRight(k) != right || Shapes.isBottom(k) != bottom) k++;
        return r[Shapes.CORNER_RADIUS[k]];
    }

    /** Signed distance from (x, y) to a rounded box of half size (bx, by) centred on the origin: negative inside. */
    private static float roundedBox(float x, float y, float bx, float by, float r) {
        float qx = Math.abs(x) - bx + r, qy = Math.abs(y) - by + r;
        float ox = Math.max(qx, 0), oy = Math.max(qy, 0);
        return Math.min(Math.max(qx, qy), 0) + (float) Math.sqrt(ox * ox + oy * oy) - r;
    }

    private static float coverage(float d, float aa) {
        return Math.clamp(0.5f - d / aa, 0f, 1f);
    }

    // Evan Wallace's blurred rounded rectangle (https://madebyevan.com/shaders/fast-rounded-rectangle-shadows/): exact
    // across x, four samples of the Gaussian along y.

    private static float erf(float x) {
        float s = Math.signum(x), a = Math.abs(x);
        float t = 1 + (0.278393f + (0.230389f + 0.078108f * (a * a)) * a) * a;
        t *= t;
        return s - s / (t * t);
    }

    private static float gaussian(float x, float sigma) {
        return (float) (Math.exp(-(x * x) / (2 * sigma * sigma)) / (2.50662827 * sigma));
    }

    private static float shadowX(float x, float y, float sigma, float corner, float bx, float by) {
        float delta = Math.min(by - corner - Math.abs(y), 0);
        float curved = bx - corner + (float) Math.sqrt(Math.max(0, corner * corner - delta * delta));
        float k = 0.70710678f / sigma;
        return 0.5f * (erf((x + curved) * k) - erf((x - curved) * k));
    }

    private static float blurredBox(float x, float y, float bx, float by, float corner, float sigma) {
        float low = y - by, high = y + by;
        float start = Math.clamp(-3 * sigma, low, high), end = Math.clamp(3 * sigma, low, high);
        float dy = (end - start) / 4, sample = start + dy * 0.5f, value = 0;
        for (int i = 0; i < 4; i++) {
            value += shadowX(x, y - sample, sigma, corner, bx, by) * gaussian(sample, sigma) * dy;
            sample += dy;
        }
        return value;
    }

    /** Whether every corner is circular (x radius = y radius), as the smooth path draws them. */
    private static boolean circular(float[] radii) {
        for (int i = 0; i < 8; i += 2) if (Math.abs(radii[i] - radii[i + 1]) > 1e-3f) return false;
        return true;
    }

    /** Whether all corners have the same circular radius, as the blur needs. */
    private static boolean oneRadius(float[] radii) {
        for (int i = 1; i < 8; i++) if (Math.abs(radii[i] - radii[0]) > 1e-3f) return false;
        return true;
    }
}
