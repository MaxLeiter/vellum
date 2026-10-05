package dev.vellum.engine.replaced;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.style.Colors;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Image;
import dev.vellum.engine.style.ImageRendering;

/**
 * {@code <img src>} and {@code <sprite src>}. An img shows a texture ({@code ns:textures/....png}, or a path
 * relative to the page), a GUI sprite ({@code sprite:ns:path}) or a canvas ({@code canvas:id}); a sprite element's
 * src is a sprite id. The natural size comes from the host (textures, sprites) or the canvas, and is re-read every
 * frame: canvases resize. Tinted by {@code -mc-tint}.
 */
final class ImageContent implements ReplacedContent {
    private final Element element;
    /** What src names, or null without one. */
    private Image source;
    private float width = Float.NaN, height = Float.NaN;

    ImageContent(Element element) {
        this.element = element;
        load();
    }

    @Override
    public float intrinsicWidth() {
        return width;
    }

    @Override
    public float intrinsicHeight() {
        return height;
    }

    @Override
    public void attributeChanged(String name) {
        if (name.equals("src")) load();
    }

    @Override
    public boolean update() {
        return refreshSize();
    }

    @Override
    public void paint(Canvas canvas, float x, float y, float w, float h) {
        if (source == null) return;
        ComputedStyle s = element.style;
        int tint = s == null ? Colors.WHITE : s.tint;
        if (source instanceof Image.Sprite sprite) {
            canvas.drawSprite(sprite.id(), x, y, w, h, tint);
            return;
        }
        String url = ImageSources.textureUrl(document(), source);
        if (url != null) canvas.drawImage(url, x, y, w, h, 0, 0, 1, 1, tint, s != null && s.imageRendering == ImageRendering.SMOOTH);
    }

    private Document document() {
        return element.ownerDocument();
    }

    private void load() {
        String src = element.getAttribute("src");
        src = src == null ? "" : src.strip();
        if (src.isEmpty()) source = null;
        else if (element.tagName().equals("sprite")) source = new Image.Sprite(src);
        else source = Image.ofUrl(src, document()::resolveUrl);
        refreshSize();
    }

    /** Reads the natural size again; true when it changed (a canvas was resized, a texture appeared). */
    private boolean refreshSize() {
        float[] size = source == null ? null : ImageSources.size(document(), source);
        float w = size == null ? Float.NaN : size[0], h = size == null ? Float.NaN : size[1];
        boolean changed = Float.compare(w, width) != 0 || Float.compare(h, height) != 0;
        width = w;
        height = h;
        return changed;
    }
}
