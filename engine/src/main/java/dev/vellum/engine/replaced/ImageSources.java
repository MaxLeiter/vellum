package dev.vellum.engine.replaced;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.style.Image;

/**
 * Where image pixels come from, for {@code <img>} and backgrounds alike: textures and sprites from the host, canvases
 * from the document ({@code canvas:id} is the {@code <canvas>} with that id).
 */
public final class ImageSources {
    private ImageSources() {}

    /**
     * The natural size {width, height} in px of a texture, sprite or canvas, or null when unknown (and for
     * gradients, which have none). The array may be shared: do not modify it.
     */
    public static float[] size(Document document, Image image) {
        return switch (image) {
            case Image.Url url -> document.host().imageSize(url.url());
            case Image.Sprite sprite -> document.host().spriteSize(sprite.id());
            case Image.Canvas c -> {
                CanvasContent canvas = CanvasContent.find(document, c.id());
                yield canvas == null ? null : canvas.size();
            }
            default -> null;
        };
    }

    /**
     * The URL {@link dev.vellum.engine.paint.Canvas#drawImage} draws a texture or canvas with; null for sprites and
     * gradients, and for a canvas that is missing or that the host cannot draw.
     */
    public static String textureUrl(Document document, Image image) {
        return switch (image) {
            case Image.Url url -> url.url();
            case Image.Canvas c -> {
                CanvasContent canvas = CanvasContent.find(document, c.id());
                yield canvas == null ? null : canvas.surface().url();
            }
            default -> null;
        };
    }
}
