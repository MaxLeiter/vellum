package dev.vellum.preview.render;

/** How a GUI sprite fills a rectangle: the {@code gui.scaling} section of its {@code .mcmeta}, as vanilla reads it. */
public sealed interface SpriteScaling {
    /** The default: the whole sprite stretched over the rectangle. */
    SpriteScaling STRETCH = new Stretch();

    record Stretch() implements SpriteScaling {}

    /** Repeats the sprite, drawn at {@code width}×{@code height} GUI px per tile. */
    record Tile(int width, int height) implements SpriteScaling {}

    /**
     * Fixed corners and edges, with the centre and edges tiled (or stretched when {@code stretchInner}). The sprite's
     * nominal size is {@code width}×{@code height} GUI px, whatever its texture resolution.
     */
    record NineSlice(int width, int height, int left, int top, int right, int bottom, boolean stretchInner)
            implements SpriteScaling {}
}
