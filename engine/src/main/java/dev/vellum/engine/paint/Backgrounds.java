package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.replaced.ImageSources;
import dev.vellum.engine.style.BackgroundLayer;
import dev.vellum.engine.style.BackgroundLayer.Repeat;
import dev.vellum.engine.style.Colors;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Image;
import dev.vellum.engine.style.ImageRendering;

import java.util.List;

/**
 * Paints {@code background-color} and the {@code background-image} layers bottom to top, with background-size,
 * -position, -repeat and -clip. The positioning area is the padding box. Colours and gradients are clipped to the
 * rounded border shape; images and sprites to the clip box's rectangle only (DESIGN §7).
 *
 * <p>Repeated textures and canvases are one {@link Canvas#drawImage} with UVs beyond 0..1 (Minecraft samples png
 * textures with REPEAT); sprites, gradients and {@code space}d tiles are drawn tile by tile. Natural sizes come from
 * {@link ImageSources}.
 */
final class Backgrounds {
    /** Tiles beyond this many per layer are not drawn (a guard against tiny tiles over huge boxes). */
    private static final int MAX_TILES = 4096;

    private final Document document;
    private final Gradients gradients = new Gradients();
    /** Painting area (the background-clip box), its radii, and the positioning area (padding box). */
    private final float[] area = new float[4], areaRadii = new float[8], origin = new float[4], scratchRadii = new float[8];
    /** Tile size, then per axis: first tile position, step between tiles, tile count. */
    private float tileW, tileH;
    private final float[] tiling = new float[6];

    Backgrounds(Document document) {
        this.document = document;
    }

    void paint(Canvas canvas, QuadBatch batch, Geometry g, ComputedStyle s, float dp) {
        List<BackgroundLayer> layers = s.backgroundLayers;
        if (!Colors.isTransparent(s.backgroundColor)) {
            // The colour is clipped like the bottom layer.
            g.area(layers.isEmpty() ? BackgroundLayer.Box.BORDER_BOX : layers.getLast().clip(), area, areaRadii);
            if (Shapes.isRounded(areaRadii)) {
                canvas.fillRoundedRect(area[0], area[1], area[2], area[3], areaRadii, s.backgroundColor);
            } else {
                canvas.fillRect(area[0], area[1], area[2], area[3], s.backgroundColor);
            }
        }
        for (int i = layers.size() - 1; i >= 0; i--) layer(canvas, batch, g, s, layers.get(i), dp);
    }

    private void layer(Canvas canvas, QuadBatch batch, Geometry g, ComputedStyle s, BackgroundLayer layer, float dp) {
        Image image = layer.image();
        if (image == null) return;
        g.area(layer.clip(), area, areaRadii);
        g.area(BackgroundLayer.Box.PADDING_BOX, origin, scratchRadii);
        if (area[2] <= 0 || area[3] <= 0) return;
        if (image instanceof Image.Sprite sprite && layer.sizeKeyword() == null
                && !layer.width().isFixed() && !layer.height().isFixed()) {
            // A sprite scales itself (stretch, tile, nine-slice): by default it simply fills the painting area.
            canvas.drawSprite(sprite.id(), area[0], area[1], area[2], area[3], s.tint);
            return;
        }
        // Textures and canvases are drawn through a URL; a canvas that is missing draws nothing.
        String texture = ImageSources.textureUrl(document, image);
        if (texture == null && (image instanceof Image.Url || image instanceof Image.Canvas)) return;
        if (!tileSize(layer, ImageSources.size(document, image))) return;
        if (!axis(0, area[0], area[2], origin[0], origin[2], tileW, layer.positionX().resolve(origin[2] - tileW), layer.repeatX())
                || !axis(3, area[1], area[3], origin[1], origin[3], tileH, layer.positionY().resolve(origin[3] - tileH), layer.repeatY())
                || tiling[2] * tiling[5] > MAX_TILES) {
            return;
        }
        boolean smooth = s.imageRendering == ImageRendering.SMOOTH;
        if (texture != null && layer.repeatX() != Repeat.SPACE && layer.repeatY() != Repeat.SPACE) {
            texture(canvas, texture, tiling[0], tiling[3], layer.repeatX() != Repeat.NO_REPEAT,
                    layer.repeatY() != Repeat.NO_REPEAT, s.tint, smooth);
            return;
        }
        // Sprites cannot be cut through UVs: clip them when tiles stick out of the painting area.
        boolean clipTiles = image instanceof Image.Sprite && tilesOverflow();
        if (clipTiles) {
            canvas.save();
            canvas.clipRect(area[0], area[1], area[2], area[3]);
        }
        for (int j = 0; j < tiling[5]; j++) {
            for (int i = 0; i < tiling[2]; i++) {
                float x = tiling[0] + i * tiling[1], y = tiling[3] + j * tiling[4];
                if (texture != null) {
                    texture(canvas, texture, x, y, false, false, s.tint, smooth);
                } else if (image instanceof Image.Sprite sprite) {
                    canvas.drawSprite(sprite.id(), x, y, tileW, tileH, s.tint);
                } else {
                    batch.setClip(area[0], area[1], area[2], area[3], areaRadii, dp);
                    batch.intersectClip(x, y, tileW, tileH);
                    gradients.paint(batch, image, x, y, tileW, tileH, dp);
                }
            }
        }
        if (clipTiles) canvas.restore();
        batch.clearClip();
        batch.flush(canvas);
    }

    /**
     * Sets the tile size from background-size and the image's intrinsic size (null when it has none, like
     * gradients, which then fill the positioning area). False when the tile is empty.
     */
    private boolean tileSize(BackgroundLayer layer, float[] intrinsic) {
        float pw = origin[2], ph = origin[3];
        boolean known = intrinsic != null && intrinsic[0] > 0 && intrinsic[1] > 0;
        float iw = known ? intrinsic[0] : pw, ih = known ? intrinsic[1] : ph;
        if (layer.sizeKeyword() != null) {
            float scale = !known ? 1 : layer.sizeKeyword().equals("cover")
                    ? Math.max(pw / iw, ph / ih) : Math.min(pw / iw, ph / ih);
            tileW = iw * scale;
            tileH = ih * scale;
        } else {
            boolean autoW = !layer.width().isFixed(), autoH = !layer.height().isFixed();
            tileW = autoW ? iw : layer.width().resolve(pw);
            tileH = autoH ? ih : layer.height().resolve(ph);
            // One auto side keeps the intrinsic ratio, or fills the area when there is none.
            if (autoW && !autoH) tileW = known ? tileH * iw / ih : pw;
            if (autoH && !autoW) tileH = known ? tileW * ih / iw : ph;
        }
        if (layer.repeatX() == Repeat.ROUND && tileW > 0) tileW = pw / Math.max(1, Math.round(pw / tileW));
        if (layer.repeatY() == Repeat.ROUND && tileH > 0) tileH = ph / Math.max(1, Math.round(ph / tileH));
        return tileW > 0 && tileH > 0 && Float.isFinite(tileW) && Float.isFinite(tileH);
    }

    /**
     * Lays tiles out along one axis into {@code tiling[at .. at+3)}: first position, step, count. The tiles cover
     * the painting area {@code [paintStart, +paintSize)}; {@code space} spreads whole tiles over the positioning area.
     */
    private boolean axis(int at, float paintStart, float paintSize, float originStart, float originSize, float tile,
                         float position, Repeat repeat) {
        float first = originStart + position, step = tile;
        if (repeat == Repeat.SPACE) {
            int fit = (int) Math.floor(originSize / tile);
            if (fit >= 2) {
                step = tile + (originSize - fit * tile) / (fit - 1);
                first = originStart;
            } else {
                repeat = Repeat.NO_REPEAT;
            }
        }
        int count = 1;
        if (repeat != Repeat.NO_REPEAT) {
            first -= (float) Math.ceil((first - paintStart) / step) * step;
            count = (int) Math.ceil((paintStart + paintSize - first) / step);
        }
        tiling[at] = first;
        tiling[at + 1] = step;
        tiling[at + 2] = count;
        return count > 0 && count <= MAX_TILES;
    }

    /**
     * Draws the texture whose tile starts at (tx, ty) over the painting area, cut through its UVs. Along a wrapping
     * axis it covers the whole area (UVs run past 1); otherwise only the tile's part of it.
     */
    private void texture(Canvas canvas, String url, float tx, float ty, boolean wrapX, boolean wrapY, int tint, boolean smooth) {
        float x0 = area[0], x1 = area[0] + area[2], y0 = area[1], y1 = area[1] + area[3];
        if (!wrapX) {
            x0 = Math.max(x0, tx);
            x1 = Math.min(x1, tx + tileW);
        }
        if (!wrapY) {
            y0 = Math.max(y0, ty);
            y1 = Math.min(y1, ty + tileH);
        }
        if (x1 <= x0 || y1 <= y0) return;
        canvas.drawImage(url, x0, y0, x1 - x0, y1 - y0,
                (x0 - tx) / tileW, (y0 - ty) / tileH, (x1 - tx) / tileW, (y1 - ty) / tileH, tint, smooth);
    }

    /** Whether any tile sticks out of the painting area. */
    private boolean tilesOverflow() {
        return tiling[0] < area[0] || tiling[3] < area[1]
                || tiling[0] + (tiling[2] - 1) * tiling[1] + tileW > area[0] + area[2]
                || tiling[3] + (tiling[5] - 1) * tiling[4] + tileH > area[1] + area[3];
    }
}
