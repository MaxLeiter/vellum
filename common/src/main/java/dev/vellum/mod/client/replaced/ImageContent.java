package dev.vellum.mod.client.replaced;

import dev.vellum.engine.dom.Element;
import dev.vellum.mod.client.render.McCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code <img src>}: a texture ({@code ns:textures/....png}, relative URLs resolve against the page), a GUI sprite
 * ({@code sprite:ns:path}) or a canvas ({@code canvas:<id>}). A texture's intrinsic size is read once from its PNG
 * header.
 */
final class ImageContent extends McReplaced {
    private static final Map<Identifier, float[]> PNG_SIZES = new HashMap<>();
    private static final float[] UNKNOWN = {Float.NaN, Float.NaN};

    private String src;
    private float[] size;

    ImageContent(Element element) {
        super(element);
        load();
    }

    @Override
    public float intrinsicWidth() {
        return size[0];
    }

    @Override
    public float intrinsicHeight() {
        return size[1];
    }

    @Override
    public void attributeChanged(String name) {
        if (name.equals("src")) load();
    }

    @Override
    public boolean update() {
        // A canvas can be resized by its script; follow it.
        if (!src.startsWith(CanvasContent.SCHEME)) return false;
        float[] now = CanvasContent.size(src);
        boolean changed = now[0] != size[0] || now[1] != size[1];
        size = now;
        return changed;
    }

    @Override
    public void draw(McCanvas canvas, Element element, float x, float y, float width, float height) {
        canvas.drawImage(src, x, y, width, height, 0, 0, 1, 1, -1, false);
    }

    /** Forgets PNG sizes; resource packs may have replaced the images. */
    static void clearCache() {
        PNG_SIZES.clear();
    }

    private void load() {
        String raw = attr("src", "");
        boolean absolute = raw.isEmpty() || raw.startsWith("sprite:") || raw.startsWith(CanvasContent.SCHEME) || element.ownerDocument() == null;
        src = absolute ? raw : element.ownerDocument().resolveUrl(raw);
        size = size(src);
    }

    /** {width, height} in px of any image URL (texture, {@code sprite:} or {@code canvas:}), or NaN when unknown. */
    static float[] size(String url) {
        if (url.startsWith("sprite:")) return SpriteContent.size(url.substring("sprite:".length()));
        if (url.startsWith(CanvasContent.SCHEME)) return CanvasContent.size(url);
        Identifier id = Identifier.tryParse(url);
        return id == null ? UNKNOWN : PNG_SIZES.computeIfAbsent(id, ImageContent::readPngSize);
    }

    /** Width and height from the PNG's IHDR chunk, without decoding the image. */
    private static float[] readPngSize(Identifier id) {
        var resource = Minecraft.getInstance().getResourceManager().getResource(id);
        if (resource.isEmpty()) return UNKNOWN;
        try (InputStream in = resource.get().open()) {
            byte[] header = in.readNBytes(24);
            if (header.length < 24 || header[1] != 'P' || header[2] != 'N' || header[3] != 'G') return UNKNOWN;
            return new float[] {readInt(header, 16), readInt(header, 20)};
        } catch (IOException e) {
            return UNKNOWN;
        }
    }

    private static int readInt(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
    }
}
