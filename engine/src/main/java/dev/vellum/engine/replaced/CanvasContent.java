package dev.vellum.engine.replaced;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.PixelSurface;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.style.Colors;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.ImageRendering;

/**
 * {@code <canvas width height>}: a {@link PixelSurface} from the host, 300×150 unless the attributes say otherwise
 * (1 to 2048 px a side). Scripts draw on it with {@code getContext('2d')} ({@link Context2D}); {@code canvas:id}
 * shows it in an {@code <img>} or a CSS background. Changing the size gives a new, cleared surface, as in browsers.
 * The surface is uploaded once per frame, before painting.
 */
public final class CanvasContent implements ReplacedContent {
    public static final int DEFAULT_WIDTH = 300, DEFAULT_HEIGHT = 150, MAX_SIZE = 2048;

    private final Element element;
    private PixelSurface surface;
    /** {width, height} of the surface, shared with {@link ImageSources#size}. */
    private final float[] size = new float[2];
    private boolean resized;

    CanvasContent(Element element) {
        this.element = element;
        allocate();
    }

    /** The live canvas whose element has this id, or null. */
    static CanvasContent find(Document document, String id) {
        for (ReplacedContent content : document.replacedContents()) {
            if (content instanceof CanvasContent canvas && id.equals(canvas.element.getAttribute("id"))) return canvas;
        }
        return null;
    }

    /** The current surface; replaced when the canvas is resized. */
    public PixelSurface surface() {
        return surface;
    }

    float[] size() {
        return size;
    }

    /** Called after drawing: the pixels are uploaded and repainted at the next frame. */
    void changed() {
        element.ownerDocument().invalidatePaint();
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
        if (name.equals("width") || name.equals("height")) {
            surface.dispose();
            allocate();
            resized = true;
        }
    }

    @Override
    public boolean update() {
        surface.upload();
        boolean changed = resized;
        resized = false;
        return changed;
    }

    @Override
    public void paint(Canvas canvas, float x, float y, float width, float height) {
        String url = surface.url();
        if (url == null) return;
        ComputedStyle s = element.style;
        canvas.drawImage(url, x, y, width, height, 0, 0, 1, 1, s == null ? Colors.WHITE : s.tint,
                s != null && s.imageRendering == ImageRendering.SMOOTH);
    }

    @Override
    public void dispose() {
        surface.dispose();
    }

    private void allocate() {
        int w = side(element.numberAttribute("width", DEFAULT_WIDTH)), h = side(element.numberAttribute("height", DEFAULT_HEIGHT));
        surface = element.ownerDocument().host().createSurface(w, h);
        size[0] = w;
        size[1] = h;
    }

    private static int side(float attribute) {
        return Math.clamp(Math.round(attribute), 1, MAX_SIZE);
    }
}
