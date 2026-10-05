package dev.vellum.preview.render;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.paint.Canvas;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * {@link Canvas} over a Java2D {@link BufferedImage}, drawing like Minecraft's GUI renderer: one GUI px is
 * {@code scale} device px, textures and glyphs are sampled nearest-neighbour, sprites are cut with vanilla's
 * scaling, text uses {@link MinecraftFont}, and clips are device-pixel rectangles like the GUI scissor. Used by the
 * previewer and the snapshot tests.
 */
public final class ImageCanvas implements Canvas {
    private record State(AffineTransform transform, Rectangle clip, float alpha) {}

    private static final AffineTransform IDENTITY = new AffineTransform();

    private final BufferedImage image;
    private final float scale;
    private final MinecraftAssets assets;
    private final MinecraftFont font;
    private final Graphics2D g;
    private final TriangleRasterizer rasterizer;
    private final Rectangle bounds;
    private final Deque<State> saved = new ArrayDeque<>();
    /** GUI → device. */
    private AffineTransform transform;
    /** In device px; replaced, never mutated. */
    private Rectangle clip;
    private float alpha = 1;
    /** What the Graphics2D currently has. */
    private Rectangle appliedClip;
    private int appliedAlpha = 255;
    private boolean appliedSmooth;

    /** Draws onto {@code image}, which must be {@code TYPE_INT_ARGB} and not a sub-image. */
    public ImageCanvas(BufferedImage image, float scale, MinecraftAssets assets, MinecraftFont font) {
        if (image.getType() != BufferedImage.TYPE_INT_ARGB) throw new IllegalArgumentException("Needs a TYPE_INT_ARGB image");
        this.image = image;
        this.scale = scale;
        this.assets = assets;
        this.font = font;
        this.g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        // Pure strokes keep shape edges where they are, so pixels are covered by their centres, as on a GPU.
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        this.rasterizer = new TriangleRasterizer(((DataBufferInt) image.getRaster().getDataBuffer()).getData(), image.getWidth());
        this.transform = AffineTransform.getScaleInstance(scale, scale);
        this.bounds = new Rectangle(image.getWidth(), image.getHeight());
        this.clip = bounds;
    }

    public BufferedImage image() { return image; }

    /** Releases the Graphics2D; the canvas must not be used afterwards. */
    public void dispose() {
        g.dispose();
    }

    // ---- State ----

    @Override
    public void save() {
        saved.push(new State(new AffineTransform(transform), clip, alpha));
    }

    @Override
    public void restore() {
        State state = saved.poll();
        if (state == null) throw new IllegalStateException("restore() without save()");
        transform = state.transform();
        clip = state.clip();
        alpha = state.alpha();
    }

    @Override
    public void translate(float dx, float dy) {
        transform.translate(dx, dy);
    }

    @Override
    public void transform(float a, float b, float c, float d, float e, float f) {
        transform.concatenate(new AffineTransform(a, b, c, d, e, f));
    }

    @Override
    public void multiplyAlpha(float alpha) {
        this.alpha *= Math.clamp(alpha, 0f, 1f);
    }

    /** Like Minecraft's scissor: the transformed rectangle's bounding box, on whole device pixels. */
    @Override
    public void clipRect(float x, float y, float width, float height) {
        Rectangle2D box = transform.createTransformedShape(new Rectangle2D.Float(x, y, width, height)).getBounds2D();
        int x0 = (int) Math.round(box.getMinX()), y0 = (int) Math.round(box.getMinY());
        int x1 = (int) Math.round(box.getMaxX()), y1 = (int) Math.round(box.getMaxY());
        Rectangle next = clip.intersection(new Rectangle(x0, y0, x1 - x0, y1 - y0));
        clip = next.isEmpty() ? new Rectangle() : next;
    }

    @Override
    public float devicePixel() {
        double det = Math.abs(transform.getDeterminant());
        return det == 0 ? 1 / scale : (float) (1 / Math.sqrt(det));
    }

    // ---- Primitives ----

    @Override
    public void fillRect(float x, float y, float width, float height, int argb) {
        fill(new Rectangle2D.Float(x, y, width, height), modulate(argb));
    }

    /** Uniform quads are Java2D polygons; quads with differing vertex colours go through the software rasterizer. */
    @Override
    public void fillQuads(float[] xy, int[] colors, int quadCount) {
        double[] device = new double[8];
        for (int q = 0; q < quadCount; q++) {
            int p = q * 8, c = q * 4;
            int c0 = modulate(colors[c]), c1 = modulate(colors[c + 1]), c2 = modulate(colors[c + 2]), c3 = modulate(colors[c + 3]);
            if (c0 == c1 && c0 == c2 && c0 == c3) {
                Path2D.Float quad = new Path2D.Float();
                quad.moveTo(xy[p], xy[p + 1]);
                for (int v = 1; v < 4; v++) quad.lineTo(xy[p + v * 2], xy[p + v * 2 + 1]);
                quad.closePath();
                fill(quad, c0);
            } else {
                // A GPU draws a quad as the triangles (0, 1, 2) and (0, 2, 3).
                transform.transform(xy, p, device, 0, 4);
                rasterizer.fill(clip, device[0], device[1], c0, device[2], device[3], c1, device[4], device[5], c2);
                rasterizer.fill(clip, device[0], device[1], c0, device[4], device[5], c2, device[6], device[7], c3);
            }
        }
    }

    @Override
    public void drawText(String text, float x, float y, FontSpec spec, int argb, int decorations, boolean shadow) {
        font.draw(text, spec, modulate(argb), decorations, shadow, new MinecraftFont.GlyphSink() {
            @Override
            public void glyph(BufferedImage glyph, AffineTransform glyphToText, int color) {
                AffineTransform glyphToGui = AffineTransform.getTranslateInstance(x, y);
                glyphToGui.concatenate(glyphToText);
                blit(glyph, new Rectangle(glyph.getWidth(), glyph.getHeight()), glyphToGui, color, false);
            }

            @Override
            public void rect(float x0, float y0, float x1, float y1, int color) {
                fill(new Rectangle2D.Float(x + x0, y + y0, x1 - x0, y1 - y0), color);
            }
        });
    }

    /** {@code canvas:} URLs (a {@code <canvas>} element's texture) draw nothing; unknown textures draw the missing texture. */
    @Override
    public void drawImage(String url, float x, float y, float width, float height,
                          float u0, float v0, float u1, float v1, int tint, boolean smooth) {
        if (url.startsWith("canvas:")) return;
        Texture texture = assets.texture(url).orElse(MinecraftAssets.MISSING);
        blit(texture.image(), u0, v0, u1, v1, x, y, width, height, modulate(tint), smooth || texture.blur());
    }

    @Override
    public void drawSprite(String spriteId, float x, float y, float width, float height, int tint) {
        Texture sprite = assets.sprite(spriteId).orElse(MinecraftAssets.MISSING);
        int color = modulate(tint);
        // blitSprite works in whole GUI px; only the origin may be fractional (McCanvas translates the pose).
        SpriteSlicer.slice(sprite.scaling(), Math.round(width), Math.round(height), (bx, by, bw, bh, u0, v0, u1, v1) ->
                blit(sprite.image(), u0, v0, u1, v1, x + bx, y + by, bw, bh, color, false));
    }

    @Override
    public void drawReplaced(ReplacedContent content, Element element, float x, float y, float width, float height) {
        if (content instanceof PaintedContent painted) painted.paint(this, x, y, width, height);
    }

    // ---- Drawing ----
    // Graphics2D state is only touched when it changes: Java2D revalidates its pipeline on every change, which would
    // otherwise dominate the cost of small blits such as glyphs.

    private int modulate(int argb) {
        if (alpha >= 1) return argb;
        return Math.round((argb >>> 24) * alpha) << 24 | argb & 0xFFFFFF;
    }

    /** Fills a shape given in GUI px with a final colour. */
    private void fill(Shape shape, int argb) {
        if ((argb >>> 24) == 0) return;
        prepare(transform, 255, false);
        g.setColor(new Color(argb, true));
        g.fill(shape);
    }

    /**
     * Draws the part of {@code img} between normalised UVs into a GUI rectangle. UVs past 0..1 repeat the image (as
     * Minecraft samples textures with REPEAT) and reversed UVs flip it.
     */
    private void blit(BufferedImage img, float u0, float v0, float u1, float v1,
                      float x, float y, float width, float height, int argb, boolean smooth) {
        float sx0 = u0 * img.getWidth(), sx1 = u1 * img.getWidth(), sy0 = v0 * img.getHeight(), sy1 = v1 * img.getHeight();
        if (sx0 == sx1 || sy0 == sy1 || width == 0 || height == 0) return;
        float scaleX = width / (sx1 - sx0), scaleY = height / (sy1 - sy0);
        AffineTransform imageToGui = new AffineTransform(scaleX, 0, 0, scaleY, x - sx0 * scaleX, y - sy0 * scaleY);
        Rectangle2D source = new Rectangle2D.Float(Math.min(sx0, sx1), Math.min(sy0, sy1), Math.abs(sx1 - sx0), Math.abs(sy1 - sy0));
        blit(img, source, imageToGui, argb, smooth);
    }

    /** Draws the {@code source} region of {@code img} (image px), placed by {@code imageToGui}, multiplied by {@code argb}. */
    private void blit(BufferedImage img, Rectangle2D source, AffineTransform imageToGui, int argb, boolean smooth) {
        int a = argb >>> 24;
        if (a == 0) return;
        AffineTransform imageToDevice = new AffineTransform(transform);
        imageToDevice.concatenate(imageToGui);
        prepare(imageToDevice, a, smooth);
        BufferedImage tinted = Tints.tint(img, argb);
        Rectangle whole = source.getBounds(), imageBounds = new Rectangle(img.getWidth(), img.getHeight());
        if (!imageBounds.contains(source)) {
            g.setPaint(new TexturePaint(tinted, imageBounds));
            g.fill(source);
        } else if (whole.equals(imageBounds) || !smooth && source.equals(whole)) {
            // Whole texels: draw just them. (Filtered sampling must still see the neighbouring texels, as on a GPU.)
            g.drawImage(whole.equals(imageBounds) ? tinted : tinted.getSubimage(whole.x, whole.y, whole.width, whole.height),
                    whole.x, whole.y, null);
        } else {
            g.clip(source);
            g.drawImage(tinted, 0, 0, null);
            appliedClip = null;
        }
    }

    /** Points the Graphics2D at the current clip, the given user → device transform, alpha and filtering. */
    private void prepare(AffineTransform userToDevice, int alpha, boolean smooth) {
        if (appliedClip != clip) {
            g.setTransform(IDENTITY);
            g.setClip(clip.equals(bounds) ? null : clip); // Java2D is faster without a clip than with a full one
            appliedClip = clip;
        }
        g.setTransform(userToDevice);
        if (alpha != appliedAlpha) {
            g.setComposite(alpha == 255 ? AlphaComposite.SrcOver : AlphaComposite.SrcOver.derive(alpha / 255f));
            appliedAlpha = alpha;
        }
        if (smooth != appliedSmooth) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, smooth
                    ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            appliedSmooth = smooth;
        }
    }
}
