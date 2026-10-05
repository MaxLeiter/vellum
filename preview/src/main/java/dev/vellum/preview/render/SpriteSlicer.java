package dev.vellum.preview.render;

/**
 * Vanilla's GUI sprite scaling, ported from {@code GuiGraphicsExtractor.blitSprite} (stretch, tile, nine-slice) and
 * {@code TiledBlitRenderState}, so sprites are cut exactly as in game, including the integer arithmetic. Produces
 * plain blits: integer rectangles relative to the sprite's origin with UVs normalised to the sprite.
 */
final class SpriteSlicer {
    interface Blits {
        void blit(int x, int y, int width, int height, float u0, float v0, float u1, float v1);
    }

    private SpriteSlicer() {}

    static void slice(SpriteScaling scaling, int width, int height, Blits out) {
        switch (scaling) {
            case SpriteScaling.Stretch ignored -> {
                if (width != 0 && height != 0) out.blit(0, 0, width, height, 0, 0, 1, 1);
            }
            case SpriteScaling.Tile t -> tiled(out, 0, 0, width, height, 0, 0, t.width(), t.height(), t.width(), t.height());
            case SpriteScaling.NineSlice n -> nineSlice(out, n, width, height);
        }
    }

    /** {@code blitNineSlicedSprite}. Borders shrink to half the size when the rectangle is small. */
    private static void nineSlice(Blits out, SpriteScaling.NineSlice n, int width, int height) {
        int left = Math.min(n.left(), width / 2), right = Math.min(n.right(), width / 2);
        int top = Math.min(n.top(), height / 2), bottom = Math.min(n.bottom(), height / 2);
        int sw = n.width(), sh = n.height();
        int innerWidth = width - right - left, innerHeight = height - bottom - top;
        int texInnerWidth = sw - right - left, texInnerHeight = sh - bottom - top;
        if (width == sw && height == sh) {
            window(out, n, 0, 0, 0, 0, width, height);
        } else if (height == sh) {
            window(out, n, 0, 0, 0, 0, left, height);
            inner(out, n, left, 0, innerWidth, height, left, 0, texInnerWidth, sh);
            window(out, n, sw - right, 0, width - right, 0, right, height);
        } else if (width == sw) {
            window(out, n, 0, 0, 0, 0, width, top);
            inner(out, n, 0, top, width, innerHeight, 0, top, sw, texInnerHeight);
            window(out, n, 0, sh - bottom, 0, height - bottom, width, bottom);
        } else {
            window(out, n, 0, 0, 0, 0, left, top);
            inner(out, n, left, 0, innerWidth, top, left, 0, texInnerWidth, top);
            window(out, n, sw - right, 0, width - right, 0, right, top);
            window(out, n, 0, sh - bottom, 0, height - bottom, left, bottom);
            inner(out, n, left, height - bottom, innerWidth, bottom, left, sh - bottom, texInnerWidth, bottom);
            window(out, n, sw - right, sh - bottom, width - right, height - bottom, right, bottom);
            inner(out, n, 0, top, left, innerHeight, 0, top, left, texInnerHeight);
            inner(out, n, left, top, innerWidth, innerHeight, left, top, texInnerWidth, texInnerHeight);
            inner(out, n, width - right, top, right, innerHeight, sw - right, top, right, texInnerHeight);
        }
    }

    /** A 1:1 window onto the sprite: {@code width}×{@code height} texels from (texX, texY) drawn at (x, y). */
    private static void window(Blits out, SpriteScaling.NineSlice n, int texX, int texY, int x, int y, int width, int height) {
        if (width == 0 || height == 0) return;
        out.blit(x, y, width, height, (float) texX / n.width(), (float) texY / n.height(),
                (float) (texX + width) / n.width(), (float) (texY + height) / n.height());
    }

    /** {@code blitNineSliceInnerSegment}: a texture region stretched or tiled over the rectangle. */
    private static void inner(Blits out, SpriteScaling.NineSlice n, int x, int y, int width, int height,
                              int texX, int texY, int texWidth, int texHeight) {
        if (width <= 0 || height <= 0) return;
        if (n.stretchInner()) {
            out.blit(x, y, width, height, (float) texX / n.width(), (float) texY / n.height(),
                    (float) (texX + texWidth) / n.width(), (float) (texY + texHeight) / n.height());
        } else {
            tiled(out, x, y, width, height, texX, texY, texWidth, texHeight, n.width(), n.height());
        }
    }

    /** {@code blitTiledSprite} + {@code TiledBlitRenderState}: whole tiles, then a cut tile with shortened UVs. */
    private static void tiled(Blits out, int x, int y, int width, int height, int texX, int texY,
                              int tileWidth, int tileHeight, int spriteWidth, int spriteHeight) {
        if (width <= 0 || height <= 0) return;
        if (tileWidth <= 0 || tileHeight <= 0) throw new IllegalArgumentException("Tile size must be positive");
        float u0 = (float) texX / spriteWidth, u1 = (float) (texX + tileWidth) / spriteWidth;
        float v0 = (float) texY / spriteHeight, v1 = (float) (texY + tileHeight) / spriteHeight;
        for (int tileX = 0; tileX < width; tileX += tileWidth) {
            int w = Math.min(tileWidth, width - tileX);
            float tileU1 = w == tileWidth ? u1 : lerp((float) w / tileWidth, u0, u1);
            for (int tileY = 0; tileY < height; tileY += tileHeight) {
                int h = Math.min(tileHeight, height - tileY);
                float tileV1 = h == tileHeight ? v1 : lerp((float) h / tileHeight, v0, v1);
                out.blit(x + tileX, y + tileY, w, h, u0, v0, tileU1, tileV1);
            }
        }
    }

    private static float lerp(float t, float a, float b) {
        return a + t * (b - a);
    }
}
