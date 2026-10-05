package dev.vellum.engine.paint;

import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Length;

import java.util.Arrays;

/**
 * Geometry of rounded rectangles and borders, and the default tessellation behind {@link Canvas#fillRoundedRect}
 * and {@link Canvas#fillBorder}.
 *
 * <p>Radii arrays hold 8 floats: the horizontal and vertical radius of the top-left, top-right, bottom-right and
 * bottom-left corners. Side arrays (border widths, colours) are top, right, bottom, left.
 *
 * <p>Paths run top-left, bottom-left, bottom-right, top-right: the winding of vanilla's {@code fill}. Each corner
 * is an arc parameterised by α from 0 to a quarter turn, starting on side {@link #FIRST} and ending on side
 * {@link #SECOND}; the straight part after corner {@code k} belongs to side {@code SECOND[k]}.
 */
public final class Shapes {
    static final int TOP = 0, RIGHT = 1, BOTTOM = 2, LEFT = 3;
    static final int ALL_SIDES = 0b1111;

    /** Corners in path order: top-left, bottom-left, bottom-right, top-right. */
    static final int[] CORNER_RADIUS = {0, 6, 4, 2};
    static final int[] FIRST = {TOP, LEFT, BOTTOM, RIGHT}, SECOND = {LEFT, BOTTOM, RIGHT, TOP};
    /** Arc point of corner k: centre + (rx·(XC·cos α + XS·sin α), ry·(YC·cos α + YS·sin α)). */
    static final int[] XC = {0, -1, 0, 1}, XS = {-1, 0, 1, 0}, YC = {-1, 0, 1, 0}, YS = {0, 1, 0, -1};

    private static final int MAX_SEGMENTS = 16;
    /** cos and sin of i/n of a quarter turn, for n = 1..16 and i = 0..n. */
    static final float[][] COS = new float[MAX_SEGMENTS + 1][], SIN = new float[MAX_SEGMENTS + 1][];

    static {
        for (int n = 1; n <= MAX_SEGMENTS; n++) {
            COS[n] = new float[n + 1];
            SIN[n] = new float[n + 1];
            for (int i = 0; i <= n; i++) {
                double a = Math.PI / 2 * i / n;
                COS[n][i] = (float) Math.cos(a);
                SIN[n][i] = (float) Math.sin(a);
            }
            // Exact endpoints so adjacent straight edges meet the arcs without hairline gaps.
            COS[n][n] = 0;
            SIN[n][n] = 1;
        }
    }

    private static final ThreadLocal<QuadBatch> SCRATCH = ThreadLocal.withInitial(QuadBatch::new);

    private Shapes() {}

    // ---- Canvas defaults ----

    public static void fillRoundedRect(Canvas canvas, float x, float y, float width, float height, float[] radii, int argb) {
        if (!isRounded(radii)) {
            canvas.fillRect(x, y, width, height, argb);
            return;
        }
        QuadBatch batch = SCRATCH.get();
        batch.roundedRect(x, y, width, height, radii, canvas.devicePixel(), argb);
        batch.flush(canvas);
    }

    public static void fillBorder(Canvas canvas, float[] outer, float[] radii, float[] widths, int[] colors) {
        QuadBatch batch = SCRATCH.get();
        batch.ring(outer[0], outer[1], outer[2], outer[3], radii, widths, colors, colors, ALL_SIDES, canvas.devicePixel());
        batch.flush(canvas);
    }

    // ---- Radii ----

    /**
     * Resolves the style's corner radii against a {@code width × height} box into {@code out} (percentages: of the
     * width horizontally, the height vertically) and scales them all down if adjacent radii would overlap, as CSS
     * does. Returns true when any corner is rounded.
     */
    public static boolean radii(ComputedStyle style, float width, float height, float[] out) {
        if (!style.hasRadius()) {
            Arrays.fill(out, 0, 8, 0);
            return false;
        }
        resolveCorner(style.radiusTopLeft, width, height, out, 0);
        resolveCorner(style.radiusTopRight, width, height, out, 2);
        resolveCorner(style.radiusBottomRight, width, height, out, 4);
        resolveCorner(style.radiusBottomLeft, width, height, out, 6);
        float f = 1;
        f = fit(f, width, out[0] + out[2]);
        f = fit(f, width, out[6] + out[4]);
        f = fit(f, height, out[1] + out[7]);
        f = fit(f, height, out[3] + out[5]);
        if (f < 1) for (int i = 0; i < 8; i++) out[i] *= f;
        return isRounded(out);
    }

    private static void resolveCorner(Length radius, float width, float height, float[] out, int i) {
        out[i] = Math.max(0, radius.resolve(width));
        out[i + 1] = Math.max(0, radius.resolve(height));
    }

    private static float fit(float f, float side, float sum) {
        return sum > side ? Math.min(f, Math.max(0, side) / sum) : f;
    }

    /**
     * The radii of the rectangle inset by the given amounts per side (negative amounts grow it): each radius
     * shrinks by the adjacent inset, clamped at zero. Square corners stay square when growing, as CSS spreads and
     * outlines do. {@code out} may be {@code radii}.
     */
    public static void insetRadii(float[] radii, float top, float right, float bottom, float left, float[] out) {
        out[0] = inset(radii[0], left);
        out[1] = inset(radii[1], top);
        out[2] = inset(radii[2], right);
        out[3] = inset(radii[3], top);
        out[4] = inset(radii[4], right);
        out[5] = inset(radii[5], bottom);
        out[6] = inset(radii[6], left);
        out[7] = inset(radii[7], bottom);
    }

    private static float inset(float radius, float by) {
        return radius <= 0 ? 0 : Math.max(0, radius - by);
    }

    public static boolean isRounded(float[] radii) {
        for (int i = 0; i < 8; i++) if (radii[i] > 0) return true;
        return false;
    }

    // ---- Paths ----

    /** Arc segments for a corner of {@code radius} GUI px: about one per two device pixels of radius. */
    static int segments(float radius, float devicePixel) {
        if (!(radius > 0)) return 0;
        return Math.clamp((int) Math.ceil(radius / devicePixel / 2), 2, MAX_SEGMENTS);
    }

    /**
     * Writes the outline of a rounded rectangle to {@code out} from index {@code offset} (x, y pairs) and returns
     * the vertex count (at most 68). Square corners contribute one vertex.
     */
    static int roundedPath(float x, float y, float w, float h, float[] radii, float devicePixel, float[] out, int offset) {
        int o = offset;
        for (int k = 0; k < 4; k++) {
            float rx = radii[CORNER_RADIUS[k]], ry = radii[CORNER_RADIUS[k] + 1];
            float cx = isRight(k) ? x + w - rx : x + rx, cy = isBottom(k) ? y + h - ry : y + ry;
            int n = segments(Math.max(rx, ry), devicePixel);
            if (n == 0) {
                out[o++] = cx;
                out[o++] = cy;
                continue;
            }
            for (int i = 0; i <= n; i++) {
                out[o++] = cx + rx * (XC[k] * COS[n][i] + XS[k] * SIN[n][i]);
                out[o++] = cy + ry * (YC[k] * COS[n][i] + YS[k] * SIN[n][i]);
            }
        }
        return (o - offset) / 2;
    }

    static boolean isRight(int corner) {
        return corner == 2 || corner == 3;
    }

    static boolean isBottom(int corner) {
        return corner == 1 || corner == 2;
    }

    /** Start of the inner edge between borders {@code a} (at the start) and {@code b}; overlapping borders meet in proportion. */
    static float innerStart(float pos, float size, float a, float b) {
        return a + b > size ? pos + size * a / (a + b) : pos + a;
    }

    static float innerEnd(float pos, float size, float a, float b) {
        return a + b > size ? pos + size * a / (a + b) : pos + size - b;
    }

    /**
     * The straight part of one side of a border ring, between the corner arcs: writes {outer start, outer end,
     * inner end, inner start} (8 floats, path order) to {@code out}.
     */
    static void sideQuad(int side, float x, float y, float w, float h, float[] radii, float[] widths, float[] out) {
        float t = widths[TOP], r = widths[RIGHT], b = widths[BOTTOM], l = widths[LEFT];
        float ix0 = innerStart(x, w, l, r), ix1 = innerEnd(x, w, l, r);
        float iy0 = innerStart(y, h, t, b), iy1 = innerEnd(y, h, t, b);
        switch (side) {
            case LEFT -> set(out, x, y + radii[1], x, y + h - radii[7],
                    ix0, iy1 - inset(radii[7], b), ix0, iy0 + inset(radii[1], t));
            case BOTTOM -> set(out, x + radii[6], y + h, x + w - radii[4], y + h,
                    ix1 - inset(radii[4], r), iy1, ix0 + inset(radii[6], l), iy1);
            case RIGHT -> set(out, x + w, y + h - radii[5], x + w, y + radii[3],
                    ix1, iy0 + inset(radii[3], t), ix1, iy1 - inset(radii[5], b));
            default -> set(out, x + w - radii[2], y, x + radii[0], y,
                    ix0 + inset(radii[0], l), iy0, ix1 - inset(radii[2], r), iy0);
        }
    }

    private static void set(float[] out, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
        out[0] = x0;
        out[1] = y0;
        out[2] = x1;
        out[3] = y1;
        out[4] = x2;
        out[5] = y2;
        out[6] = x3;
        out[7] = y3;
    }

    // ---- Hit testing ----

    /** Whether ({@code px}, {@code py}) lies inside the rounded rectangle (half-open on the right and bottom). */
    public static boolean contains(float x, float y, float w, float h, float[] radii, float px, float py) {
        if (!(px >= x && py >= y && px < x + w && py < y + h)) return false;
        if (radii == null) return true;
        for (int k = 0; k < 4; k++) {
            float rx = radii[CORNER_RADIUS[k]], ry = radii[CORNER_RADIUS[k] + 1];
            if (rx <= 0 || ry <= 0) continue;
            float cx = isRight(k) ? x + w - rx : x + rx, cy = isBottom(k) ? y + h - ry : y + ry;
            float dx = (px - cx) / rx, dy = (py - cy) / ry;
            boolean inCornerX = isRight(k) ? dx > 0 : dx < 0, inCornerY = isBottom(k) ? dy > 0 : dy < 0;
            if (inCornerX && inCornerY && dx * dx + dy * dy > 1) return false;
        }
        return true;
    }
}
