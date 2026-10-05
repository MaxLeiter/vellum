package dev.vellum.engine.paint;

import dev.vellum.engine.testing.RecordingCanvas;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backgrounds of a 40x20 box at the top left, from CSS. */
class BackgroundsTest {
    private static final int RED = 0xFFFF0000, BLUE = 0xFF0000FF;

    /** Paints a 40x20 div with {@code css}; {@code a.png} is {@code imageWidth}×{@code imageHeight} (0 for unknown). */
    private static RecordingCanvas paint(String css, float imageWidth, float imageHeight) {
        return paint(css, imageWidth, imageHeight, new RecordingCanvas());
    }

    private static RecordingCanvas paint(String css, float imageWidth, float imageHeight, RecordingCanvas canvas) {
        TestHost host = new TestHost();
        if (imageWidth > 0) host.imageSizes.put("test:a.png", new float[] {imageWidth, imageHeight});
        return host.load("<div style='width: 40px; height: 20px; " + css + "'></div>").paint(canvas);
    }

    private static RecordingCanvas paint(String css) {
        return paint(css, 0, 0);
    }

    private static float[] image(String css, float imageWidth, float imageHeight) {
        return paint(css, imageWidth, imageHeight).ops("drawImage").getFirst().args();
    }

    @Test
    void colourIsClippedLikeTheBottomLayerAndRounded() {
        RecordingCanvas c = paint("border: 2px solid transparent; border-top-left-radius: 6px; background: red url(a.png) padding-box");
        RecordingCanvas.Call fill = c.ops("fillRoundedRect").getFirst();
        assertArrayEquals(new float[] {2, 2, 36, 16, 4, 4, 0, 0, 0, 0, 0, 0}, fill.args(), 1e-4f);
    }

    @Test
    void colourWithoutAnImageKeepsItsClip() {
        // background-image: none is still a layer, whose clip is the colour's (the bottom layer's).
        RecordingCanvas c = paint("border: 2px solid transparent; background-clip: padding-box; background-color: #333");
        assertEquals("rect 2,2 36x16 #ff333333", c.trace("rect").getFirst());
        c = paint("border: 2px solid transparent; background: #333 content-box; padding: 3px");
        assertEquals("rect 5,5 30x10 #ff333333", c.trace("rect").getFirst());
    }

    @Test
    void repeatedTexturesAreOneDrawWithWrappingUvs() {
        RecordingCanvas c = paint("background: url(a.png)", 16, 16);
        assertEquals(1, c.ops("drawImage").size());
        assertArrayEquals(new float[] {0, 0, 40, 20, 0, 0, 2.5f, 1.25f}, c.ops("drawImage").getFirst().args(), 1e-5f);
    }

    @Test
    void positionedRepeatStartsMidTile() {
        // Tiles start at 4 - 16: UVs 0.75..3.25, which samples the same as -0.25..2.25 with REPEAT.
        assertArrayEquals(new float[] {0, 0, 40, 16, 0.75f, 0, 3.25f, 1}, image("background: url(a.png) 4px 0 repeat-x", 16, 16), 1e-5f);
    }

    @Test
    void noRepeatCentred() {
        assertArrayEquals(new float[] {12, 2, 16, 16, 0, 0, 1, 1}, image("background: url(a.png) center no-repeat", 16, 16), 1e-5f);
    }

    @Test
    void coverAndContainKeepTheAspectRatio() {
        assertArrayEquals(new float[] {0, 0, 40, 10, 0, 0, 1, 1}, image("background: url(a.png) 0 0 / contain no-repeat", 32, 8), 1e-5f);
        // 80×20, cut to the box through the UVs
        assertArrayEquals(new float[] {0, 0, 40, 20, 0, 0, 0.5f, 1}, image("background: url(a.png) 0 0 / cover no-repeat", 32, 8), 1e-5f);
    }

    @Test
    void oneAutoSideKeepsTheRatio() {
        assertArrayEquals(new float[] {0, 0, 32, 16, 0, 0, 1, 1}, image("background: url(a.png) 0 0 / 32px auto no-repeat", 16, 8), 1e-5f);
    }

    @Test
    void spaceSpreadsWholeTiles() {
        List<RecordingCanvas.Call> draws = paint("background: url(a.png) space no-repeat", 16, 16).ops("drawImage");
        assertEquals(2, draws.size());
        assertEquals(0, draws.get(0).x(), 1e-5);
        assertEquals(24, draws.get(1).x(), 1e-5);
    }

    @Test
    void roundRescalesTilesToFit() {
        // 40 / 16 = 2.5 rounds to 3 tiles of 13.3px
        assertEquals(3, image("background: url(a.png) round no-repeat", 16, 16)[6], 1e-5);
    }

    @Test
    void spritesStretchOverThePaintingAreaByDefault() {
        RecordingCanvas.Call sprite = paint("background: sprite(minecraft:widget/button)").ops("drawSprite").getFirst();
        assertEquals("minecraft:widget/button", sprite.text());
        assertArrayEquals(new float[] {0, 0, 40, 20}, sprite.args(), 1e-5f);
        assertNull(sprite.clip());
    }

    @Test
    void sizedSpritesTileAndClip() {
        List<RecordingCanvas.Call> sprites = paint("background: sprite(minecraft:x) 0 0 / 16px 16px").ops("drawSprite");
        assertEquals(6, sprites.size());
        for (RecordingCanvas.Call s : sprites) assertArrayEquals(new float[] {0, 0, 40, 20}, s.clip(), 1e-5f);
    }

    @Test
    void linearGradientRunsAlongItsAngle() {
        RecordingCanvas c = paint("background: linear-gradient(90deg, red, blue)");
        assertEquals(800, c.quadArea(), 1e-2);
        c.forEachVertex((x, y, color) -> {
            if (x == 0) assertEquals(RED, color);
            if (x == 40) assertEquals(BLUE, color);
        });
    }

    @Test
    void gradientsFollowTheBorderRadius() {
        RecordingCanvas fine = new RecordingCanvas();
        fine.devicePixel = 0.25f;
        RecordingCanvas c = paint("border-top-left-radius: 8px; border-bottom-right-radius: 8px; "
                + "background: linear-gradient(red, blue)", 0, 0, fine);
        assertEquals(800 - 2 * (1 - Math.PI / 4) * 64, c.quadArea(), 2);
        float[] radii = {8, 8, 0, 0, 8, 8, 0, 0};
        // Chords of the clip polygon lie inside the arc; allow the clip polygon's own vertices on the arc.
        c.forEachVertex((x, y, color) -> assertTrue(Shapes.contains(-0.3f, -0.3f, 40.6f, 20.6f, radii, x, y), x + "," + y));
    }

    @Test
    void repeatingGradientsTileTheirStops() {
        RecordingCanvas c = paint("background: repeating-linear-gradient(90deg, red 0, blue 10px)");
        assertEquals(800, c.quadArea(), 1e-2);
        c.forEachVertex((x, y, color) -> {
            float phase = x % 10;
            if (phase > 0.01f && phase < 9.99f) assertEquals(QuadBatch.lerpArgb(RED, BLUE, phase / 10), color, "at " + x);
        });
    }

    @Test
    void radialGradientsAreRingsFromTheCentre() {
        RecordingCanvas c = paint("background: radial-gradient(circle at 50% 50%, red, blue)");
        assertEquals(800, c.quadArea(), 1);
        c.forEachVertex((x, y, color) -> {
            if (x == 20 && y == 10) assertEquals(RED, color);
        });
    }

    @Test
    void sizedGradientsTile() {
        RecordingCanvas c = paint("background: linear-gradient(90deg, red, blue) 0 0 / 10px 10px");
        assertEquals(800, c.quadArea(), 1e-2);
        c.forEachVertex((x, y, color) -> {
            if (x == 10 || x == 30) assertTrue(color == RED || color == BLUE);
        });
    }

    @Test
    void fadingToTransparentKeepsTheColour() {
        paint("background: linear-gradient(90deg, red, transparent)")
                .forEachVertex((x, y, color) -> assertEquals(0xFF0000, color & 0xFFFFFF));
    }
}
