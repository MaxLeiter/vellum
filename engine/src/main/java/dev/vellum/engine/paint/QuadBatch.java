package dev.vellum.engine.paint;

import java.util.Arrays;

/**
 * Collects convex polygons as quads for a single {@link Canvas#fillQuads} call, optionally clipping them to a
 * convex region first (the rounded border shape for gradients, the padding box for inset shadows). Instances are
 * reused across frames, so tessellating does not allocate once the buffers have grown.
 *
 * <p>Winding: every quad has the vertex order of vanilla's {@code fill}, (x0,y0) (x0,y1) (x1,y1) (x1,y0), which is
 * a negative signed area in GUI coordinates (y down). Minecraft's GUI pipeline culls back faces, so generators may
 * emit either orientation and the batch normalises it; zero-area and fully transparent pieces are dropped.
 */
final class QuadBatch {
    private float[] xy = new float[8 * 32];
    private int[] colors = new int[4 * 32];
    private int count;

    /** Convex clip polygon (vertex count 0 = no clip), its bounds, and an inner rectangle needing no clipping. */
    private float[] clip = new float[2 * 72];
    private int clipCount;
    private float clipSign;
    private float clipMinX, clipMinY, clipMaxX, clipMaxY;
    private float safeMinX, safeMinY, safeMaxX, safeMaxY;

    /** Ping-pong buffers for clipping polygons with per-vertex colours. */
    private float[] polyA = new float[2 * 96], polyB = new float[2 * 96];
    private int[] colA = new int[96], colB = new int[96];

    /** Scratch for paths and quads built by {@link #roundedRect} and {@link #ring}. */
    private final float[] path = new float[2 * 72], side = new float[8];
    /** The corner {@link #ring} is tessellating: index, outer and inner centres and radii. */
    private int corner;
    private float ocx, ocy, orx, ory, icx, icy, irx, iry;

    int count() { return count; }

    /** Sends the collected quads to the canvas and starts over. The arrays stay owned by this batch. */
    void flush(Canvas canvas) {
        if (count > 0) canvas.fillQuads(xy, colors, count);
        count = 0;
    }

    // ---- Clip region ----

    /** Clips everything emitted from now on to the convex polygon {@code poly[0 .. 2n)}. */
    void setClip(float[] poly, int n) {
        storeClip(poly, n);
        safeMinX = safeMinY = Float.POSITIVE_INFINITY;
        safeMaxX = safeMaxY = Float.NEGATIVE_INFINITY;
    }

    /** Clips to the rounded rectangle; the corner-free middle is accepted without clipping. */
    void setClip(float x, float y, float w, float h, float[] radii, float devicePixel) {
        int n = Shapes.roundedPath(x, y, w, h, radii, devicePixel, polyA, 0);
        setClip(polyA, n);
        safeMinX = x + Math.max(radii[0], radii[6]);
        safeMaxX = x + w - Math.max(radii[2], radii[4]);
        safeMinY = y + Math.max(radii[1], radii[3]);
        safeMaxY = y + h - Math.max(radii[5], radii[7]);
    }

    /** Intersects the current clip region (which must be set) with an axis-aligned rectangle. */
    void intersectClip(float x, float y, float w, float h) {
        if (clipCount < 0) return;
        ensurePoly(clipCount + 8);
        System.arraycopy(clip, 0, polyA, 0, 2 * clipCount);
        // The rectangle's edges, walked so that its inside is on the positive side.
        int n = clipHalfPlane(polyA, null, clipCount, x, y + h, x, y, 1, polyB, null);
        n = clipHalfPlane(polyB, null, n, x, y, x + w, y, 1, polyA, null);
        n = clipHalfPlane(polyA, null, n, x + w, y, x + w, y + h, 1, polyB, null);
        n = clipHalfPlane(polyB, null, n, x + w, y + h, x, y + h, 1, polyA, null);
        float sx0 = Math.max(safeMinX, x), sy0 = Math.max(safeMinY, y);
        float sx1 = Math.min(safeMaxX, x + w), sy1 = Math.min(safeMaxY, y + h);
        storeClip(polyA, n);
        safeMinX = sx0;
        safeMinY = sy0;
        safeMaxX = sx1;
        safeMaxY = sy1;
        if (n < 3) clipCount = -1; // empty: everything is clipped away
    }

    void clearClip() {
        clipCount = 0;
    }

    private void storeClip(float[] poly, int n) {
        if (clip.length < 2 * n) clip = new float[2 * n + 16];
        System.arraycopy(poly, 0, clip, 0, 2 * n);
        clipCount = n;
        clipMinX = clipMinY = Float.POSITIVE_INFINITY;
        clipMaxX = clipMaxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < clipCount; i++) {
            clipMinX = Math.min(clipMinX, clip[2 * i]);
            clipMaxX = Math.max(clipMaxX, clip[2 * i]);
            clipMinY = Math.min(clipMinY, clip[2 * i + 1]);
            clipMaxY = Math.max(clipMaxY, clip[2 * i + 1]);
        }
        clipSign = Math.signum(signedArea(clip, clipCount));
    }

    // ---- Shapes ----

    /** A filled rounded rectangle: it is convex, so one fan. */
    void roundedRect(float x, float y, float w, float h, float[] radii, float devicePixel, int argb) {
        polygon(path, null, argb, Shapes.roundedPath(x, y, w, h, radii, devicePixel, path, 0));
    }

    /**
     * The band between the rounded rectangle (x, y, w, h, radii) and the same rectangle inset by {@code widths}
     * (top, right, bottom, left): a border. Inner radii are the outer ones minus the adjacent widths. Each side's
     * straight part and its share of the two adjacent corners (split along the corner's diagonal, as CSS does) take
     * that side's colours, {@code outerColors} on the outer edge and {@code innerColors} on the inner edge, so a ring
     * can also ramp (shadow blur). Sides missing from {@code straightMask} get only their corner shares.
     */
    void ring(float x, float y, float w, float h, float[] radii, float[] widths, int[] outerColors, int[] innerColors,
              int straightMask, float devicePixel) {
        float ix0 = Shapes.innerStart(x, w, widths[Shapes.LEFT], widths[Shapes.RIGHT]);
        float ix1 = Shapes.innerEnd(x, w, widths[Shapes.LEFT], widths[Shapes.RIGHT]);
        float iy0 = Shapes.innerStart(y, h, widths[Shapes.TOP], widths[Shapes.BOTTOM]);
        float iy1 = Shapes.innerEnd(y, h, widths[Shapes.TOP], widths[Shapes.BOTTOM]);
        for (int k = 0; k < 4; k++) {
            orx = radii[Shapes.CORNER_RADIUS[k]];
            ory = radii[Shapes.CORNER_RADIUS[k] + 1];
            int n = Shapes.segments(Math.max(orx, ory), devicePixel);
            if (n == 0) continue; // a square corner is covered by the straight parts' diagonal joins
            boolean right = Shapes.isRight(k), bottom = Shapes.isBottom(k);
            corner = k;
            irx = Math.max(0, orx - widths[right ? Shapes.RIGHT : Shapes.LEFT]);
            iry = Math.max(0, ory - widths[bottom ? Shapes.BOTTOM : Shapes.TOP]);
            ocx = right ? x + w - orx : x + orx;
            ocy = bottom ? y + h - ory : y + ory;
            icx = right ? ix1 - irx : ix0 + irx;
            icy = bottom ? iy1 - iry : iy0 + iry;
            int first = Shapes.FIRST[k], second = Shapes.SECOND[k];
            // The diagonal from the outer to the inner corner divides the arc between its two sides.
            double split = Math.atan2(widths[first], widths[second]);
            float splitCos = (float) Math.cos(split), splitSin = (float) Math.sin(split);
            float splitAt = (float) (split / (Math.PI / 2) * n);
            float[] cos = Shapes.COS[n], sin = Shapes.SIN[n];
            for (int i = 0; i < n; i++) {
                if (splitAt > i && splitAt < i + 1) {
                    cornerPiece(cos[i], sin[i], splitCos, splitSin, outerColors[first], innerColors[first]);
                    cornerPiece(splitCos, splitSin, cos[i + 1], sin[i + 1], outerColors[second], innerColors[second]);
                } else {
                    int s = i + 0.5f < splitAt ? first : second;
                    cornerPiece(cos[i], sin[i], cos[i + 1], sin[i + 1], outerColors[s], innerColors[s]);
                }
            }
        }
        float[] q = side;
        for (int s = 0; s < 4; s++) {
            if ((straightMask & (1 << s)) == 0) continue;
            Shapes.sideQuad(s, x, y, w, h, radii, widths, q);
            quad(q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7], outerColors[s], outerColors[s], innerColors[s], innerColors[s]);
        }
    }

    /** The part of the current corner's band between arc parameters (cos, sin) 0 and 1. */
    private void cornerPiece(float c0, float s0, float c1, float s1, int outer, int inner) {
        int k = corner;
        float ux0 = Shapes.XC[k] * c0 + Shapes.XS[k] * s0, uy0 = Shapes.YC[k] * c0 + Shapes.YS[k] * s0;
        float ux1 = Shapes.XC[k] * c1 + Shapes.XS[k] * s1, uy1 = Shapes.YC[k] * c1 + Shapes.YS[k] * s1;
        quad(ocx + orx * ux0, ocy + ory * uy0, ocx + orx * ux1, ocy + ory * uy1,
                icx + irx * ux1, icy + iry * uy1, icx + irx * ux0, icy + iry * uy0,
                outer, outer, inner, inner);
    }

    // ---- Emitting ----

    void rect(float x, float y, float w, float h, int argb) {
        quad(x, y, x, y + h, x + w, y + h, x + w, y, argb, argb, argb, argb);
    }

    void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, int argb) {
        quad(x0, y0, x1, y1, x2, y2, x3, y3, argb, argb, argb, argb);
    }

    /** A convex quad (or a triangle with a repeated vertex) with per-vertex colours, in either orientation. */
    void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
              int c0, int c1, int c2, int c3) {
        if (((c0 | c1 | c2 | c3) >>> 24) == 0) return;
        float[] p = polyA;
        p[0] = x0; p[1] = y0; p[2] = x1; p[3] = y1; p[4] = x2; p[5] = y2; p[6] = x3; p[7] = y3;
        colA[0] = c0; colA[1] = c1; colA[2] = c2; colA[3] = c3;
        if (clipCount != 0) clipAndFan(4);
        else fan(polyA, colA, 4);
    }

    /** A convex polygon with per-vertex colours ({@code colors} may be null for {@code uniform}). */
    void polygon(float[] poly, int[] vertexColors, int uniform, int n) {
        if (n < 3) return;
        ensurePoly(n + Math.max(clipCount, 4) + 4);
        System.arraycopy(poly, 0, polyA, 0, 2 * n);
        if (vertexColors != null) System.arraycopy(vertexColors, 0, colA, 0, n);
        else Arrays.fill(colA, 0, n, uniform);
        if (clipCount != 0) clipAndFan(n);
        else fan(polyA, colA, n);
    }

    private void clipAndFan(int n) {
        if (clipCount < 0) return;
        float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = Float.NEGATIVE_INFINITY, maxY = maxX;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, polyA[2 * i]);
            maxX = Math.max(maxX, polyA[2 * i]);
            minY = Math.min(minY, polyA[2 * i + 1]);
            maxY = Math.max(maxY, polyA[2 * i + 1]);
        }
        if (maxX <= clipMinX || minX >= clipMaxX || maxY <= clipMinY || minY >= clipMaxY) return;
        if (minX >= safeMinX && maxX <= safeMaxX && minY >= safeMinY && maxY <= safeMaxY) {
            fan(polyA, colA, n);
            return;
        }
        ensurePoly(n + clipCount + 4);
        float[] in = polyA, out = polyB;
        int[] inC = colA, outC = colB;
        for (int i = 0; i < clipCount && n > 0; i++) {
            int j = i + 1 == clipCount ? 0 : i + 1;
            n = clipHalfPlane(in, inC, n, clip[2 * i], clip[2 * i + 1], clip[2 * j], clip[2 * j + 1], clipSign, out, outC);
            float[] t = in; in = out; out = t;
            int[] tc = inC; inC = outC; outC = tc;
        }
        fan(in, inC, n);
    }

    /**
     * Emits a convex polygon (one of this batch's buffers) as a fan of quads (v0, vi, vi+1, vi+2), oriented like
     * vanilla quads. Repeated vertices are dropped first, so a leftover triangle is a quad with its last vertex
     * repeated.
     */
    private void fan(float[] p, int[] col, int n) {
        int m = 0;
        for (int i = 0; i < n; i++) {
            float x = p[2 * i], y = p[2 * i + 1];
            if (m > 0 && x == p[2 * m - 2] && y == p[2 * m - 1]) continue;
            p[2 * m] = x;
            p[2 * m + 1] = y;
            col[m++] = col[i];
        }
        while (m > 1 && p[0] == p[2 * m - 2] && p[1] == p[2 * m - 1]) m--;
        n = m;
        if (n < 3) return;
        boolean reverse = signedArea(p, n) > 0;
        for (int i = 1; i + 1 < n; i += 2) {
            int i1 = i, i2 = i + 1, i3 = Math.min(i + 2, n - 1);
            if (reverse) {
                i1 = n - i;
                i2 = n - i - 1;
                i3 = Math.max(n - i - 2, 1);
            }
            float x0 = p[0], y0 = p[1];
            float x1 = p[2 * i1], y1 = p[2 * i1 + 1], x2 = p[2 * i2], y2 = p[2 * i2 + 1], x3 = p[2 * i3], y3 = p[2 * i3 + 1];
            float area = (x2 - x0) * (y3 - y1) - (x3 - x1) * (y2 - y0);
            if (area < 0) append(x0, y0, x1, y1, x2, y2, x3, y3, col[0], col[i1], col[i2], col[i3]);
        }
    }

    private void append(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
                        int c0, int c1, int c2, int c3) {
        if (count * 8 + 8 > xy.length) {
            xy = Arrays.copyOf(xy, xy.length * 2);
            colors = Arrays.copyOf(colors, colors.length * 2);
        }
        int o = count * 8;
        xy[o] = x0; xy[o + 1] = y0; xy[o + 2] = x1; xy[o + 3] = y1;
        xy[o + 4] = x2; xy[o + 5] = y2; xy[o + 6] = x3; xy[o + 7] = y3;
        int k = count * 4;
        colors[k] = c0; colors[k + 1] = c1; colors[k + 2] = c2; colors[k + 3] = c3;
        count++;
    }

    private void ensurePoly(int n) {
        if (colA.length >= n) return;
        int cap = Math.max(n, colA.length * 2);
        polyA = Arrays.copyOf(polyA, 2 * cap);
        polyB = Arrays.copyOf(polyB, 2 * cap);
        colA = Arrays.copyOf(colA, cap);
        colB = Arrays.copyOf(colB, cap);
    }

    // ---- Geometry helpers ----

    /** Twice the signed area (shoelace) of {@code p[0 .. 2n)}; negative for vanilla's winding. */
    static float signedArea(float[] p, int n) {
        float s = 0;
        for (int i = 0; i < n; i++) {
            int j = i + 1 == n ? 0 : i + 1;
            s += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1];
        }
        return s;
    }

    /**
     * One Sutherland–Hodgman step: keeps the part of polygon {@code in} on the inner side of the directed line
     * a→b, where inner means {@code sign * cross(b - a, p - a) >= 0}. Colours (nullable) are interpolated linearly
     * per channel, as the GPU would across the original edge. Returns the output vertex count.
     */
    static int clipHalfPlane(float[] in, int[] inC, int n, float ax, float ay, float bx, float by, float sign,
                             float[] out, int[] outC) {
        float ex = bx - ax, ey = by - ay;
        int m = 0;
        for (int i = 0; i < n; i++) {
            int j = i + 1 == n ? 0 : i + 1;
            float px = in[2 * i], py = in[2 * i + 1], qx = in[2 * j], qy = in[2 * j + 1];
            float dp = sign * (ex * (py - ay) - ey * (px - ax));
            float dq = sign * (ex * (qy - ay) - ey * (qx - ax));
            if (dp >= 0) {
                out[2 * m] = px;
                out[2 * m + 1] = py;
                if (outC != null) outC[m] = inC[i];
                m++;
            }
            if ((dp >= 0) != (dq >= 0)) {
                float t = dp / (dp - dq);
                out[2 * m] = px + (qx - px) * t;
                out[2 * m + 1] = py + (qy - py) * t;
                if (outC != null) outC[m] = lerpArgb(inC[i], inC[j], t);
                m++;
            }
        }
        return m;
    }

    /** Per-channel linear interpolation of ARGB (not premultiplied: this matches vertex colour interpolation). */
    static int lerpArgb(int c0, int c1, float t) {
        if (c0 == c1) return c0;
        int a = lerpChannel(c0 >>> 24, c1 >>> 24, t);
        int r = lerpChannel((c0 >> 16) & 0xFF, (c1 >> 16) & 0xFF, t);
        int g = lerpChannel((c0 >> 8) & 0xFF, (c1 >> 8) & 0xFF, t);
        int b = lerpChannel(c0 & 0xFF, c1 & 0xFF, t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpChannel(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }
}
