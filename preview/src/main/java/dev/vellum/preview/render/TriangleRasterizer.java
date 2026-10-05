package dev.vellum.preview.render;

import java.awt.Rectangle;

/**
 * Fills triangles with per-vertex colours straight into an ARGB pixel array, the way a GPU does: samples at pixel
 * centres, colours interpolated linearly (non-premultiplied, like vertex colours), the top-left fill rule so
 * triangles that share an edge never both cover a pixel, and source-over blending. Java2D cannot interpolate
 * colours across a triangle; this is the fallback for gradient quads.
 */
final class TriangleRasterizer {
    private final int[] pixels;
    private final int stride;

    TriangleRasterizer(int[] pixels, int stride) {
        this.pixels = pixels;
        this.stride = stride;
    }

    /**
     * An edge a→b of a positively wound triangle. Its edge function is twice the area of (a, b, p): positive inside,
     * and proportional to the barycentric weight of the opposite vertex.
     */
    private record Edge(double ax, double ay, double dx, double dy, boolean topLeft) {
        static Edge of(double ax, double ay, double bx, double by) {
            double dx = bx - ax, dy = by - ay;
            // With y growing downwards and positive winding, top edges run right and left edges run up.
            return new Edge(ax, ay, dx, dy, dy < 0 || (dy == 0 && dx > 0));
        }

        double at(double px, double py) {
            return dx * (py - ay) - dy * (px - ax);
        }

        /** Inside, or exactly on the edge when it is a top or left edge. */
        boolean covers(double w) {
            return w > 0 || (w == 0 && topLeft);
        }
    }

    /** Fills the triangle (device px) inside {@code clip}. */
    void fill(Rectangle clip, double x0, double y0, int c0, double x1, double y1, int c1, double x2, double y2, int c2) {
        double area = Edge.of(x0, y0, x1, y1).at(x2, y2);
        if (area == 0) return;
        if (area < 0) { // swap two vertices so the winding is positive
            double tx = x1, ty = y1;
            int tc = c1;
            x1 = x2; y1 = y2; c1 = c2;
            x2 = tx; y2 = ty; c2 = tc;
            area = -area;
        }
        Edge e0 = Edge.of(x1, y1, x2, y2), e1 = Edge.of(x2, y2, x0, y0), e2 = Edge.of(x0, y0, x1, y1);
        int minX = Math.max(clip.x, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(clip.x + clip.width, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(clip.y, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(clip.y + clip.height, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        double inverse = 1 / area;
        for (int py = minY; py < maxY; py++) {
            double sy = py + 0.5;
            for (int px = minX; px < maxX; px++) {
                double sx = px + 0.5;
                double w0 = e0.at(sx, sy), w1 = e1.at(sx, sy), w2 = e2.at(sx, sy);
                if (!e0.covers(w0) || !e1.covers(w1) || !e2.covers(w2)) continue;
                int i = py * stride + px;
                pixels[i] = blend(pixels[i], interpolate(c0, c1, c2, w0 * inverse, w1 * inverse, w2 * inverse));
            }
        }
    }

    private static int interpolate(int c0, int c1, int c2, double l0, double l1, double l2) {
        return channel(c0 >>> 24, c1 >>> 24, c2 >>> 24, l0, l1, l2) << 24
                | channel(c0 >> 16 & 0xFF, c1 >> 16 & 0xFF, c2 >> 16 & 0xFF, l0, l1, l2) << 16
                | channel(c0 >> 8 & 0xFF, c1 >> 8 & 0xFF, c2 >> 8 & 0xFF, l0, l1, l2) << 8
                | channel(c0 & 0xFF, c1 & 0xFF, c2 & 0xFF, l0, l1, l2);
    }

    private static int channel(int a, int b, int c, double l0, double l1, double l2) {
        int v = (int) (a * l0 + b * l1 + c * l2 + 0.5);
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /** Source-over of non-premultiplied ARGB. */
    private static int blend(int dst, int src) {
        int sa = src >>> 24;
        if (sa == 255) return src;
        if (sa == 0) return dst;
        int da = dst >>> 24;
        if (da == 255) { // opaque destination, the common case: integer maths
            int keep = 255 - sa;
            return 0xFF000000
                    | ((src >> 16 & 0xFF) * sa + (dst >> 16 & 0xFF) * keep + 127) / 255 << 16
                    | ((src >> 8 & 0xFF) * sa + (dst >> 8 & 0xFF) * keep + 127) / 255 << 8
                    | ((src & 0xFF) * sa + (dst & 0xFF) * keep + 127) / 255;
        }
        float a = sa / 255f, d = da / 255f * (1 - a), out = a + d;
        int result = Math.round(out * 255) << 24;
        for (int shift = 0; shift < 24; shift += 8) {
            result |= Math.round((((src >>> shift) & 0xFF) * a + ((dst >>> shift) & 0xFF) * d) / out) << shift;
        }
        return result;
    }
}
