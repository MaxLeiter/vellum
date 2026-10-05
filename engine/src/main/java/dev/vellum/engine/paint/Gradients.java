package dev.vellum.engine.paint;

import dev.vellum.engine.style.Colors;
import dev.vellum.engine.style.Image;

import java.util.List;

/**
 * Tessellates gradient images into a {@link QuadBatch}: linear gradients (any angle, any number of stops, repeating)
 * as bands between colour stops, radial gradients as concentric rings. Pieces are generously sized; the batch's clip
 * region trims them to the painted area (the rounded border shape intersected with the image tile).
 */
final class Gradients {
    /** Repeating gradients with very short periods are capped at this many bands. */
    private static final int MAX_BANDS = 2048;

    private float[] stopPos = new float[8];
    private int[] stopColor = new int[8];
    private int stopCount;

    /** The gradient line of the linear gradient being drawn: start point, direction, perpendicular half-extent. */
    private float startX, startY, dirX, dirY, halfWidth;

    /** The rings of the radial gradient being drawn: centre, vertical scale, unit circle samples. */
    private float centerX, centerY, yScale;
    private float[] ringCos = new float[65], ringSin = new float[65];
    private int ringSegments;

    /** Draws a gradient image filling the tile (x, y, w, h). Other images are ignored. */
    void paint(QuadBatch batch, Image image, float x, float y, float w, float h, float devicePixel) {
        switch (image) {
            case Image.LinearGradient g -> linear(batch, g, x, y, w, h);
            case Image.RadialGradient g -> radial(batch, g, x, y, w, h, devicePixel);
            default -> {}
        }
    }

    // ---- Linear ----

    private void linear(QuadBatch batch, Image.LinearGradient g, float x, float y, float w, float h) {
        double angle = Math.toRadians(g.angleDeg());
        dirX = (float) Math.sin(angle);
        dirY = (float) -Math.cos(angle);
        // The gradient line passes through the centre and is long enough for its ends to touch opposite corners.
        float length = Math.abs(w * dirX) + Math.abs(h * dirY);
        if (!(length > 0) || !resolveStops(g.stops(), length)) return;
        startX = x + w / 2 - dirX * length / 2;
        startY = y + h / 2 - dirY * length / 2;
        halfWidth = (float) Math.hypot(w, h) / 2 + 1;
        int last = stopCount - 1;
        float period = stopPos[last] - stopPos[0];
        if (!g.repeating()) {
            band(batch, -1, stopPos[0], stopColor[0], stopColor[0]);
            stops(batch, 0);
            band(batch, stopPos[last], length + 1, stopColor[last], stopColor[last]);
        } else if (period < 1e-3f || (length / period) * stopCount > MAX_BANDS) {
            band(batch, -1, length + 1, stopColor[last], stopColor[last]);
        } else {
            int from = (int) Math.floor(-stopPos[last] / period), to = (int) Math.ceil((length - stopPos[0]) / period);
            for (int k = from; k <= to; k++) stops(batch, k * period);
        }
    }

    /** The bands between consecutive stops, shifted along the line by {@code offset}. */
    private void stops(QuadBatch batch, float offset) {
        for (int i = 0; i + 1 < stopCount; i++) {
            band(batch, stopPos[i] + offset, stopPos[i + 1] + offset, stopColor[i], stopColor[i + 1]);
        }
    }

    /** The band where the gradient line runs from {@code s0} to {@code s1}, shading from c0 to c1. */
    private void band(QuadBatch batch, float s0, float s1, int c0, int c1) {
        if (!(s1 > s0)) return;
        c0 = premultipliedEnd(c0, c1);
        c1 = premultipliedEnd(c1, c0);
        float px = -dirY * halfWidth, py = dirX * halfWidth;
        float ax = startX + dirX * s0, ay = startY + dirY * s0, bx = startX + dirX * s1, by = startY + dirY * s1;
        batch.quad(ax + px, ay + py, ax - px, ay - py, bx - px, by - py, bx + px, by + py, c0, c0, c1, c1);
    }

    // ---- Radial ----

    private void radial(QuadBatch batch, Image.RadialGradient g, float x, float y, float w, float h, float dp) {
        centerX = x + g.centerX().resolve(w);
        centerY = y + g.centerY().resolve(h);
        float left = Math.abs(centerX - x), right = Math.abs(x + w - centerX);
        float top = Math.abs(centerY - y), bottom = Math.abs(y + h - centerY);
        float fx = Math.max(left, right), fy = Math.max(top, bottom);
        float rx, ry;
        if (g.circle()) {
            rx = ry = (float) Math.hypot(fx, fy);
        } else {
            // farthest-corner ellipse: the aspect ratio closest-side would give, scaled through the farthest corner
            float csx = Math.min(left, right), csy = Math.min(top, bottom);
            if (csx > 0 && csy > 0) {
                float k = csx / csy;
                ry = (float) Math.hypot(fx / k, fy);
                rx = k * ry;
            } else {
                rx = fx * (float) Math.sqrt(2);
                ry = fy * (float) Math.sqrt(2);
            }
        }
        if (!(rx > 0) || !(ry > 0) || !resolveStops(g.stops(), rx)) return;
        yScale = ry / rx;
        int last = stopCount - 1;
        // The ending shape passes through the farthest corner, so radius rx covers the tile.
        float outer = Math.max(rx, stopPos[last]) + 1;
        ringSegments = Math.clamp(4L * Shapes.segments(Math.max(outer, outer * yScale), dp), 8, 64);
        for (int j = 0; j <= ringSegments; j++) {
            double a = 2 * Math.PI * j / ringSegments;
            ringCos[j] = (float) Math.cos(a);
            ringSin[j] = (float) Math.sin(a);
        }
        ring(batch, 0, stopPos[0], stopColor[0], stopColor[0]);
        for (int i = 0; i < last; i++) ring(batch, stopPos[i], stopPos[i + 1], stopColor[i], stopColor[i + 1]);
        ring(batch, stopPos[last], outer, stopColor[last], stopColor[last]);
    }

    /** The ring between radii r0 and r1 (along the horizontal ray), shading from c0 inside to c1 outside. */
    private void ring(QuadBatch batch, float r0, float r1, int c0, int c1) {
        r0 = Math.max(0, r0);
        if (!(r1 > r0)) return;
        c0 = premultipliedEnd(c0, c1);
        c1 = premultipliedEnd(c1, c0);
        float ry0 = r0 * yScale, ry1 = r1 * yScale;
        for (int j = 0; j < ringSegments; j++) {
            float cos0 = ringCos[j], sin0 = ringSin[j], cos1 = ringCos[j + 1], sin1 = ringSin[j + 1];
            batch.quad(centerX + r0 * cos0, centerY + ry0 * sin0, centerX + r1 * cos0, centerY + ry1 * sin0,
                    centerX + r1 * cos1, centerY + ry1 * sin1, centerX + r0 * cos1, centerY + ry0 * sin1,
                    c0, c1, c1, c0);
        }
    }

    // ---- Stops ----

    /**
     * Resolves stop positions against the gradient length: missing first and last positions are 0 and 100%,
     * other missing ones are spread evenly, and each position is at least the previous one. False when empty.
     */
    private boolean resolveStops(List<Image.ColorStop> stops, float length) {
        int n = stops.size();
        if (n == 0) return false;
        if (stopPos.length < n) {
            stopPos = new float[n];
            stopColor = new int[n];
        }
        stopCount = n;
        for (int i = 0; i < n; i++) {
            Image.ColorStop stop = stops.get(i);
            stopColor[i] = stop.color();
            stopPos[i] = stop.position() == null ? Float.NaN : stop.position().resolve(length);
        }
        if (Float.isNaN(stopPos[0])) stopPos[0] = 0;
        if (Float.isNaN(stopPos[n - 1])) stopPos[n - 1] = Math.max(length, stopPos[0]);
        for (int i = 1; i < n; i++) {
            if (!Float.isNaN(stopPos[i])) {
                stopPos[i] = Math.max(stopPos[i], stopPos[i - 1]);
                continue;
            }
            int j = i;
            while (Float.isNaN(stopPos[j])) j++;
            float from = stopPos[i - 1], to = Math.max(stopPos[j], from);
            for (int k = i; k < j; k++) stopPos[k] = from + (to - from) * (k - i + 1) / (j - i + 1);
        }
        return true;
    }

    /**
     * Vertex colours interpolate per channel, but CSS interpolates premultiplied: a fully transparent end takes
     * the other end's RGB so fading to {@code transparent} does not darken. (Partial alpha differences keep the
     * small per-channel error.)
     */
    private static int premultipliedEnd(int c, int other) {
        return Colors.alpha(c) == 0 ? other & 0x00FFFFFF : c;
    }
}
