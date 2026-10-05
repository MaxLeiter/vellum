package dev.vellum.preview.render;

import java.awt.image.BufferedImage;

/**
 * A decoded texture (ARGB, the first frame if animated) with what its {@code .mcmeta} says about drawing it:
 * {@code texture.blur} (linear filtering) and, for GUI sprites, {@code gui.scaling}.
 */
public record Texture(BufferedImage image, boolean blur, SpriteScaling scaling) {
    /** Natural size in GUI px: a nine-slice's nominal size, otherwise one GUI px per texel. */
    public int naturalWidth() {
        return scaling instanceof SpriteScaling.NineSlice n ? n.width() : image.getWidth();
    }

    public int naturalHeight() {
        return scaling instanceof SpriteScaling.NineSlice n ? n.height() : image.getHeight();
    }
}
