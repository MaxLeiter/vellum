package dev.vellum.preview.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The vanilla sprite-scaling port, against blits worked out by hand from GuiGraphicsExtractor. */
class SpriteSlicerTest {
    private static final SpriteScaling.NineSlice BUTTON = new SpriteScaling.NineSlice(200, 20, 3, 3, 3, 3, false);

    /** Blits as "x,y wxh u0,v0-u1,v1" with UVs in texels of a {@code spriteWidth}×{@code spriteHeight} sprite. */
    private static List<String> slice(SpriteScaling scaling, int width, int height, int spriteWidth, int spriteHeight) {
        List<String> blits = new ArrayList<>();
        SpriteSlicer.slice(scaling, width, height, (x, y, w, h, u0, v0, u1, v1) -> blits.add(String.format(Locale.ROOT,
                "%d,%d %dx%d %.1f,%.1f-%.1f,%.1f", x, y, w, h, u0 * spriteWidth, v0 * spriteHeight, u1 * spriteWidth, v1 * spriteHeight)));
        return blits;
    }

    @Test
    void stretchDrawsTheWholeSprite() {
        assertEquals(List.of("0,0 30x7 0.0,0.0-16.0,16.0"), slice(SpriteScaling.STRETCH, 30, 7, 16, 16));
    }

    @Test
    void nineSliceAtItsOwnSizeIsOneBlit() {
        assertEquals(List.of("0,0 200x20 0.0,0.0-200.0,20.0"), slice(BUTTON, 200, 20, 200, 20));
    }

    @Test
    void nineSliceAtItsOwnHeightCutsOnlyColumns() {
        // The middle is tiled from the 194 texel wide centre, cut to 94.
        assertEquals(List.of(
                "0,0 3x20 0.0,0.0-3.0,20.0",
                "3,0 94x20 3.0,0.0-97.0,20.0",
                "97,0 3x20 197.0,0.0-200.0,20.0"), slice(BUTTON, 100, 20, 200, 20));
    }

    @Test
    void nineSliceTilesEdgesAndCentre() {
        SpriteScaling.NineSlice frame = new SpriteScaling.NineSlice(8, 8, 2, 2, 2, 2, false);
        assertEquals(List.of(
                "0,0 2x2 0.0,0.0-2.0,2.0",
                "2,0 4x2 2.0,0.0-6.0,2.0", "6,0 2x2 2.0,0.0-4.0,2.0",
                "8,0 2x2 6.0,0.0-8.0,2.0",
                "0,8 2x2 0.0,6.0-2.0,8.0",
                "2,8 4x2 2.0,6.0-6.0,8.0", "6,8 2x2 2.0,6.0-4.0,8.0",
                "8,8 2x2 6.0,6.0-8.0,8.0",
                "0,2 2x4 0.0,2.0-2.0,6.0", "0,6 2x2 0.0,2.0-2.0,4.0",
                "2,2 4x4 2.0,2.0-6.0,6.0", "2,6 4x2 2.0,2.0-6.0,4.0", "6,2 2x4 2.0,2.0-4.0,6.0", "6,6 2x2 2.0,2.0-4.0,4.0",
                "8,2 2x4 6.0,2.0-8.0,6.0", "8,6 2x2 6.0,2.0-8.0,4.0"), slice(frame, 10, 10, 8, 8));
    }

    @Test
    void bordersShrinkToHalfOfSmallRects() {
        assertEquals(List.of(
                "0,0 2x2 0.0,0.0-2.0,2.0",
                "2,0 2x2 198.0,0.0-200.0,2.0",
                "0,2 2x2 0.0,18.0-2.0,20.0",
                "2,2 2x2 198.0,18.0-200.0,20.0"), slice(BUTTON, 4, 4, 200, 20));
    }

    @Test
    void stretchInnerStretchesTheCentre() {
        SpriteScaling.NineSlice frame = new SpriteScaling.NineSlice(100, 100, 10, 10, 10, 10, true);
        assertEquals("10,10 30x20 10.0,10.0-90.0,90.0", slice(frame, 50, 40, 100, 100).get(7));
    }

    @Test
    void tilesRepeatAndCutTheLastOne() {
        assertEquals(List.of(
                "0,0 16x16 0.0,0.0-16.0,16.0", "0,16 16x4 0.0,0.0-16.0,4.0",
                "16,0 16x16 0.0,0.0-16.0,16.0", "16,16 16x4 0.0,0.0-16.0,4.0",
                "32,0 8x16 0.0,0.0-8.0,16.0", "32,16 8x4 0.0,0.0-8.0,4.0"), slice(new SpriteScaling.Tile(16, 16), 40, 20, 16, 16));
    }
}
