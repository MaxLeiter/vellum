package dev.vellum.engine.replaced;

import dev.vellum.engine.css.CssColors;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.PixelSurface;
import dev.vellum.engine.style.Colors;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Locale;

/**
 * A canvas's 2D drawing context ({@code canvas.getContext('2d')}): the practical subset of
 * {@code CanvasRenderingContext2D} that a pixel surface supports without a rasterizer.
 * <ul>
 *   <li>State: {@code fillStyle} and {@code strokeStyle} (CSS colours), {@code lineWidth}, {@code globalAlpha},
 *       {@code save()}/{@code restore()}.</li>
 *   <li>Rectangles: {@code fillRect}, {@code strokeRect}, {@code clearRect}. Coordinates are canvas pixels and edges
 *       snap to whole pixels (no antialiasing, no transforms).</li>
 *   <li>Pixels: {@code getImageData}, {@code putImageData} (which replaces, ignoring {@code globalAlpha}, as in
 *       browsers), and {@code drawImage} of another canvas (nearest-neighbour scaling).</li>
 * </ul>
 * Fills composite source-over. Not supported: text, paths, transforms, gradients and patterns, and drawing images
 * other than canvases. A canvas that is not in the document is drawn on all the same; it is disposed with the
 * document.
 */
public final class Context2D {
    private record State(int fill, int stroke, float lineWidth, float alpha) {}

    private final Element element;
    private int fill = Colors.BLACK, stroke = Colors.BLACK;
    private float lineWidth = 1, alpha = 1;
    private final Deque<State> saved = new ArrayDeque<>();
    private int[] scratch = new int[0];

    public Context2D(Element canvas) {
        this.element = canvas;
    }

    /** The canvas element. */
    public Element canvas() {
        return element;
    }

    // ---- State ----

    public String fillStyle() { return serialize(fill); }
    public String strokeStyle() { return serialize(stroke); }
    public float lineWidth() { return lineWidth; }
    public float globalAlpha() { return alpha; }

    /** Sets the fill colour from CSS; values that are not colours are ignored, as in browsers. */
    public void setFillStyle(String css) {
        Integer c = color(css);
        if (c != null) fill = c;
    }

    public void setStrokeStyle(String css) {
        Integer c = color(css);
        if (c != null) stroke = c;
    }

    /** Ignored unless positive and finite. */
    public void setLineWidth(float width) {
        if (width > 0 && Float.isFinite(width)) lineWidth = width;
    }

    /** Ignored outside 0..1. */
    public void setGlobalAlpha(float a) {
        if (a >= 0 && a <= 1) alpha = a;
    }

    public void save() {
        saved.push(new State(fill, stroke, lineWidth, alpha));
    }

    public void restore() {
        State s = saved.poll();
        if (s == null) return;
        fill = s.fill;
        stroke = s.stroke;
        lineWidth = s.lineWidth;
        alpha = s.alpha;
    }

    // ---- Rectangles ----

    public void fillRect(float x, float y, float w, float h) {
        fill(x, y, x + w, y + h, fill);
    }

    /** Strokes the rectangle's outline, {@code lineWidth} wide and centred on the edges. */
    public void strokeRect(float x, float y, float w, float h) {
        float half = lineWidth / 2, x0 = Math.min(x, x + w), y0 = Math.min(y, y + h), x1 = Math.max(x, x + w), y1 = Math.max(y, y + h);
        fill(x0 - half, y0 - half, x1 + half, y0 + half, stroke);
        fill(x0 - half, y1 - half, x1 + half, y1 + half, stroke);
        fill(x0 - half, y0 + half, x0 + half, y1 - half, stroke);
        fill(x1 - half, y0 + half, x1 + half, y1 - half, stroke);
    }

    /** Sets the rectangle to transparent black. */
    public void clearRect(float x, float y, float w, float h) {
        CanvasContent content = content();
        if (content == null) return;
        int[] r = pixelRect(content.surface(), x, y, x + w, y + h);
        if (r == null) return;
        content.surface().fillRect(r[0], r[1], r[2], r[3], 0);
        content.changed();
    }

    // ---- Pixels ----

    /**
     * Copies the pixels of {@code (sx, sy, sw, sh)} as RGBA bytes (non-premultiplied, row by row) into {@code rgba}
     * from {@code offset}; pixels outside the canvas are transparent black.
     */
    public void getImageData(int sx, int sy, int sw, int sh, byte[] rgba, int offset) {
        Arrays.fill(rgba, offset, offset + sw * sh * 4, (byte) 0);
        CanvasContent content = content();
        if (content == null) return;
        PixelSurface surface = content.surface();
        int x0 = Math.max(sx, 0), y0 = Math.max(sy, 0), x1 = Math.min(sx + sw, surface.width()), y1 = Math.min(sy + sh, surface.height());
        if (x1 <= x0 || y1 <= y0) return;
        int w = x1 - x0;
        int[] row = scratch(w);
        for (int y = y0; y < y1; y++) {
            surface.getPixels(x0, y, w, 1, row);
            int at = offset + ((y - sy) * sw + (x0 - sx)) * 4;
            for (int i = 0; i < w; i++, at += 4) {
                int p = row[i];
                rgba[at] = (byte) (p >> 16);
                rgba[at + 1] = (byte) (p >> 8);
                rgba[at + 2] = (byte) p;
                rgba[at + 3] = (byte) (p >>> 24);
            }
        }
    }

    /**
     * Writes an image of {@code width}×{@code height} RGBA bytes (from {@code offset}) with its top-left at
     * {@code (dx, dy)}, limited to its region {@code (dirtyX, dirtyY, dirtyW, dirtyH)}. Replaces the pixels.
     */
    public void putImageData(byte[] rgba, int offset, int width, int height, int dx, int dy,
                             int dirtyX, int dirtyY, int dirtyW, int dirtyH) {
        CanvasContent content = content();
        if (content == null) return;
        PixelSurface surface = content.surface();
        if (dirtyW < 0) {
            dirtyX += dirtyW;
            dirtyW = -dirtyW;
        }
        if (dirtyH < 0) {
            dirtyY += dirtyH;
            dirtyH = -dirtyH;
        }
        int sx0 = Math.max(0, dirtyX), sy0 = Math.max(0, dirtyY);
        int sx1 = Math.min(width, dirtyX + dirtyW), sy1 = Math.min(height, dirtyY + dirtyH);
        sx0 = Math.max(sx0, -dx);
        sy0 = Math.max(sy0, -dy);
        sx1 = Math.min(sx1, surface.width() - dx);
        sy1 = Math.min(sy1, surface.height() - dy);
        if (sx1 <= sx0 || sy1 <= sy0) return;
        int w = sx1 - sx0;
        int[] row = scratch(w);
        for (int y = sy0; y < sy1; y++) {
            int at = offset + (y * width + sx0) * 4;
            for (int i = 0; i < w; i++, at += 4) {
                row[i] = (rgba[at + 3] & 0xFF) << 24 | (rgba[at] & 0xFF) << 16 | (rgba[at + 1] & 0xFF) << 8 | rgba[at + 2] & 0xFF;
            }
            surface.setPixels(dx + sx0, dy + y, w, 1, row);
        }
        content.changed();
    }

    /**
     * Draws the source rectangle {@code (sx, sy, sw, sh)} of another canvas into {@code (dx, dy, dw, dh)}, scaled
     * nearest-neighbour and composited with {@code globalAlpha}.
     */
    public void drawImage(Context2D source, float sx, float sy, float sw, float sh, float dx, float dy, float dw, float dh) {
        CanvasContent from = source.content(), to = content();
        if (from == null || to == null || sw == 0 || sh == 0) return;
        int[] dest = pixelRect(to.surface(), dx, dy, dx + dw, dy + dh);
        if (dest == null) return;
        // The source rectangle, read once (it may be this canvas).
        PixelSurface src = from.surface();
        int[] srcRect = pixelRect(src, sx, sy, sx + sw, sy + sh);
        if (srcRect == null) return;
        int[] pixels = new int[srcRect[2] * srcRect[3]];
        src.getPixels(srcRect[0], srcRect[1], srcRect[2], srcRect[3], pixels);
        float scaleX = sw / dw, scaleY = sh / dh;
        PixelSurface surface = to.surface();
        int[] row = scratch(dest[2]);
        for (int y = dest[1]; y < dest[1] + dest[3]; y++) {
            int ty = (int) Math.floor(sy + (y + 0.5f - dy) * scaleY) - srcRect[1];
            surface.getPixels(dest[0], y, dest[2], 1, row);
            for (int i = 0; i < dest[2]; i++) {
                int tx = (int) Math.floor(sx + (dest[0] + i + 0.5f - dx) * scaleX) - srcRect[0];
                if (tx < 0 || ty < 0 || tx >= srcRect[2] || ty >= srcRect[3]) continue;
                row[i] = over(Colors.withAlphaFactor(pixels[ty * srcRect[2] + tx], alpha), row[i]);
            }
            surface.setPixels(dest[0], y, dest[2], 1, row);
        }
        to.changed();
    }

    // ---- Internals ----

    /** The canvas's content (created now for a canvas not yet in the document), or null if it is no canvas. */
    private CanvasContent content() {
        return element.ownerDocument().replacedContent(element) instanceof CanvasContent c ? c : null;
    }

    /** Fills a rectangle (any corner order) with {@code argb} faded by {@code globalAlpha}, source-over. */
    private void fill(float x0, float y0, float x1, float y1, int argb) {
        int color = Colors.withAlphaFactor(argb, alpha);
        CanvasContent content = content();
        if (Colors.isTransparent(color) || content == null) return;
        PixelSurface surface = content.surface();
        int[] r = pixelRect(surface, x0, y0, x1, y1);
        if (r == null) return;
        if (Colors.alpha(color) == 255) {
            surface.fillRect(r[0], r[1], r[2], r[3], color);
        } else {
            int[] row = scratch(r[2]);
            for (int y = r[1]; y < r[1] + r[3]; y++) {
                surface.getPixels(r[0], y, r[2], 1, row);
                for (int i = 0; i < r[2]; i++) row[i] = over(color, row[i]);
                surface.setPixels(r[0], y, r[2], 1, row);
            }
        }
        content.changed();
    }

    /**
     * The pixels a rectangle covers by their centres, clipped to the surface, as {x, y, width, height}; null when
     * none.
     */
    private static int[] pixelRect(PixelSurface surface, float x0, float y0, float x1, float y1) {
        int left = Math.max(0, Math.round(Math.min(x0, x1))), right = Math.min(surface.width(), Math.round(Math.max(x0, x1)));
        int top = Math.max(0, Math.round(Math.min(y0, y1))), bottom = Math.min(surface.height(), Math.round(Math.max(y0, y1)));
        return right > left && bottom > top ? new int[] {left, top, right - left, bottom - top} : null;
    }

    /** {@code src} over {@code dst}, both non-premultiplied ARGB. */
    static int over(int src, int dst) {
        int sa = Colors.alpha(src);
        if (sa == 255) return src;
        if (sa == 0) return dst;
        int da = Colors.alpha(dst) * (255 - sa) / 255, a = sa + da;
        return Colors.argb(a, (Colors.red(src) * sa + Colors.red(dst) * da) / a,
                (Colors.green(src) * sa + Colors.green(dst) * da) / a, (Colors.blue(src) * sa + Colors.blue(dst) * da) / a);
    }

    private int[] scratch(int length) {
        if (scratch.length < length) scratch = new int[length];
        return scratch;
    }

    private Integer color(String css) {
        return CssColors.parse(css, element.style != null ? element.style.color : Colors.BLACK);
    }

    /** As browsers serialize canvas colours: {@code #rrggbb} when opaque, else {@code rgba(r, g, b, a)}. */
    private static String serialize(int argb) {
        if (Colors.alpha(argb) == 255) return String.format(Locale.ROOT, "#%06x", argb & 0xFFFFFF);
        return CssColors.serialize(argb);
    }
}
