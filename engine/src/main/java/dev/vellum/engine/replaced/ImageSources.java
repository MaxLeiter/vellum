package dev.vellum.engine.replaced;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.style.Image;

/**
 * Where image pixels come from, for {@code <img>} and backgrounds alike: textures and sprites from the host, canvases
 * from the document ({@code canvas:id} is the {@code <canvas>} with that id).
 */
public final class ImageSources {
    private ImageSources() {}

    /** The live canvas a {@code canvas:id} image names, or null when it is missing or the image is not a canvas. */
    public static CanvasContent canvas(Document document, Image image) {
        return image instanceof Image.Canvas c ? CanvasContent.find(document, c.id()) : null;
    }

    /**
     * The natural size {width, height} in px of a texture, sprite or canvas, or null when unknown (and for
     * gradients, which have none). The array may be shared: do not modify it.
     */
    public static float[] size(Document document, Image image) {
        return size(document, image, canvas(document, image));
    }

    /** {@link #size(Document, Image)} with the image's {@link #canvas} already looked up. */
    public static float[] size(Document document, Image image, CanvasContent canvas) {
        return switch (image) {
            case Image.Url url -> document.host().imageSize(url.url());
            case Image.Sprite sprite -> document.host().spriteSize(sprite.id());
            case Image.Canvas c -> canvas == null ? null : canvas.size();
            default -> null;
        };
    }

    /**
     * The URL {@link dev.vellum.engine.paint.Canvas#drawImage} draws a texture or canvas with; null for sprites and
     * gradients, and for a canvas that is missing or that the host cannot draw.
     */
    public static String textureUrl(Document document, Image image) {
        return textureUrl(image, canvas(document, image));
    }

    /** {@link #textureUrl(Document, Image)} with the image's {@link #canvas} already looked up. */
    public static String textureUrl(Image image, CanvasContent canvas) {
        return switch (image) {
            case Image.Url url -> url.url();
            case Image.Canvas c -> canvas == null ? null : canvas.surface().url();
            default -> null;
        };
    }
}
