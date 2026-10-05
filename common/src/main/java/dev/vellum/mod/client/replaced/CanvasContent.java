package dev.vellum.mod.client.replaced;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vellum.engine.dom.Element;
import dev.vellum.mod.Constants;
import dev.vellum.mod.client.render.McCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code <canvas width="300" height="150">}: a pixel surface backed by a {@link NativeImage} in a
 * {@link DynamicTexture}. Its {@link #url()} ({@code canvas:<n>}) works anywhere an image URL does, e.g.
 * {@code <img src>} or a CSS background.
 *
 * <p>The pixel methods below are the Java surface the script binding's 2D context drives (INTEGRATION: the scripting
 * workstream wires {@code getContext('2d')} to them). Changes are uploaded once per frame. Render thread only.
 */
public final class CanvasContent extends McReplaced {
    public static final String SCHEME = "canvas:";
    private static final int MAX_SIZE = 2048;
    private static final Map<String, CanvasContent> LIVE = new HashMap<>();
    private static int nextId;

    private final String url = SCHEME + nextId++;
    private final Identifier textureId = Constants.id("canvas/" + url.substring(SCHEME.length()));
    private @Nullable DynamicTexture texture;
    private boolean dirty, resized;

    CanvasContent(Element element) {
        super(element);
        allocate();
        LIVE.put(url, this);
    }

    /** The texture registered for a {@code canvas:} URL, or null when no such canvas is live. */
    public static @Nullable Identifier texture(String url) {
        CanvasContent c = LIVE.get(url);
        return c == null ? null : c.textureId;
    }

    /** {width, height} of a live canvas, or NaN. */
    static float[] size(String url) {
        CanvasContent c = LIVE.get(url);
        return c == null ? new float[] {Float.NaN, Float.NaN} : new float[] {c.width(), c.height()};
    }

    public String url() {
        return url;
    }

    public int width() {
        return pixels().getWidth();
    }

    public int height() {
        return pixels().getHeight();
    }

    // ---- Pixel API (ARGB colours; out-of-range coordinates are clipped) ----

    public void fillRect(int x, int y, int width, int height, int argb) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y), x1 = Math.min(width(), x + width), y1 = Math.min(height(), y + height);
        if (x1 <= x0 || y1 <= y0) return;
        pixels().fillRect(x0, y0, x1 - x0, y1 - y0, argb);
        dirty = true;
    }

    public void clearRect(int x, int y, int width, int height) {
        fillRect(x, y, width, height, 0);
    }

    public void setPixel(int x, int y, int argb) {
        if (x < 0 || y < 0 || x >= width() || y >= height()) return;
        pixels().setPixel(x, y, argb);
        dirty = true;
    }

    public int getPixel(int x, int y) {
        return x < 0 || y < 0 || x >= width() || y >= height() ? 0 : pixels().getPixel(x, y);
    }

    /** Marks the pixels changed; they are uploaded before the next frame is drawn. */
    public void upload() {
        dirty = true;
    }

    // ---- ReplacedContent ----

    @Override
    public float intrinsicWidth() {
        return width();
    }

    @Override
    public float intrinsicHeight() {
        return height();
    }

    @Override
    public void attributeChanged(String name) {
        if (name.equals("width") || name.equals("height")) {
            release();
            allocate();
            resized = true;
        }
    }

    @Override
    public boolean update() {
        if (dirty && texture != null) {
            texture.upload();
            dirty = false;
        }
        boolean changed = resized;
        resized = false;
        return changed;
    }

    @Override
    public void draw(McCanvas canvas, Element element, float x, float y, float width, float height) {
        canvas.blit(textureId, x, y, width, height, 0, 0, 1, 1, -1, false);
    }

    @Override
    public void dispose() {
        LIVE.remove(url);
        release();
    }

    private NativeImage pixels() {
        if (texture == null) throw new IllegalStateException("Canvas " + url + " was disposed");
        return texture.getPixels();
    }

    private void allocate() {
        int w = Math.clamp((int) number("width", 300), 1, MAX_SIZE), h = Math.clamp((int) number("height", 150), 1, MAX_SIZE);
        texture = new DynamicTexture(() -> "Vellum " + url, new NativeImage(w, h, true));
        Minecraft.getInstance().getTextureManager().register(textureId, texture);
    }

    private void release() {
        if (texture != null) Minecraft.getInstance().getTextureManager().release(textureId);
        texture = null;
    }
}
