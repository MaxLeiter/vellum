package dev.vellum.engine.paint;

import dev.vellum.engine.layout.Box;
import dev.vellum.engine.style.BackgroundLayer;
import dev.vellum.engine.style.BackgroundLayer.Repeat;
import dev.vellum.engine.style.Image;
import dev.vellum.engine.style.Length;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static dev.vellum.engine.paint.TestTree.style;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundsTest {
    private static final int RED = 0xFFFF0000, BLUE = 0xFF0000FF;
    private final TestTree t = new TestTree();
    private final Box box = t.div(0, 0, 40, 20, 0);
    private final RecordingCanvas canvas = new RecordingCanvas();

    private RecordingCanvas paint(BackgroundLayer... layers) {
        style(box).backgroundLayers = List.of(layers);
        return t.paint(box, canvas);
    }

    private static BackgroundLayer layer(Image image, String keyword, Length w, Length h, Length x, Length y,
                                         Repeat rx, Repeat ry, BackgroundLayer.Box clip) {
        return new BackgroundLayer(image, keyword, w, h, x, y, rx, ry, clip);
    }

    private static BackgroundLayer sized(Image image, float w, float h, Repeat repeat) {
        return layer(image, null, Length.px(w), Length.px(h), Length.ZERO, Length.ZERO, repeat, repeat, BackgroundLayer.Box.BORDER_BOX);
    }

    @Test
    void colourIsClippedLikeTheBottomLayerAndRounded() {
        box.borderTop = box.borderRight = box.borderBottom = box.borderLeft = 2;
        style(box).backgroundColor = RED;
        style(box).radiusTopLeft = Length.px(6);
        RecordingCanvas c = paint(layer(new Image.Url("a.png"), null, Length.AUTO, Length.AUTO, Length.ZERO, Length.ZERO,
                Repeat.REPEAT, Repeat.REPEAT, BackgroundLayer.Box.PADDING_BOX));
        RecordingCanvas.Call fill = c.ops("fillRoundedRect").getFirst();
        assertArrayEquals(new float[] {2, 2, 36, 16, 4, 4, 0, 0, 0, 0, 0, 0}, fill.args(), 1e-4f);
    }

    @Test
    void repeatedTexturesAreOneDrawWithWrappingUvs() {
        canvas.imageSizes.put("a.png", new float[] {16, 16});
        RecordingCanvas c = paint(BackgroundLayer.simple(new Image.Url("a.png")));
        assertEquals(1, c.ops("drawImage").size());
        assertArrayEquals(new float[] {0, 0, 40, 20, 0, 0, 2.5f, 1.25f}, c.ops("drawImage").getFirst().args(), 1e-5f);
    }

    @Test
    void positionedRepeatStartsMidTile() {
        canvas.imageSizes.put("a.png", new float[] {16, 16});
        RecordingCanvas c = paint(layer(new Image.Url("a.png"), null, Length.AUTO, Length.AUTO, Length.px(4), Length.ZERO,
                Repeat.REPEAT, Repeat.NO_REPEAT, BackgroundLayer.Box.BORDER_BOX));
        // Tiles start at 4 - 16: UVs 0.75..3.25, which samples the same as -0.25..2.25 with REPEAT.
        assertArrayEquals(new float[] {0, 0, 40, 16, 0.75f, 0, 3.25f, 1}, c.ops("drawImage").getFirst().args(), 1e-5f);
    }

    @Test
    void noRepeatCentred() {
        canvas.imageSizes.put("a.png", new float[] {16, 16});
        RecordingCanvas c = paint(layer(new Image.Url("a.png"), null, Length.AUTO, Length.AUTO, Length.PERCENT_50,
                Length.PERCENT_50, Repeat.NO_REPEAT, Repeat.NO_REPEAT, BackgroundLayer.Box.BORDER_BOX));
        assertArrayEquals(new float[] {12, 2, 16, 16, 0, 0, 1, 1}, c.ops("drawImage").getFirst().args(), 1e-5f);
    }

    @Test
    void coverAndContainKeepTheAspectRatio() {
        canvas.imageSizes.put("a.png", new float[] {32, 8});
        RecordingCanvas c = paint(layer(new Image.Url("a.png"), "contain", Length.AUTO, Length.AUTO, Length.ZERO, Length.ZERO,
                Repeat.NO_REPEAT, Repeat.NO_REPEAT, BackgroundLayer.Box.BORDER_BOX));
        assertArrayEquals(new float[] {0, 0, 40, 10, 0, 0, 1, 1}, c.ops("drawImage").getFirst().args(), 1e-5f);
        c.calls.clear();
        paint(layer(new Image.Url("a.png"), "cover", Length.AUTO, Length.AUTO, Length.ZERO, Length.ZERO,
                Repeat.NO_REPEAT, Repeat.NO_REPEAT, BackgroundLayer.Box.BORDER_BOX));
        // 80×20, cut to the box through the UVs
        assertArrayEquals(new float[] {0, 0, 40, 20, 0, 0, 0.5f, 1}, c.ops("drawImage").getFirst().args(), 1e-5f);
    }

    @Test
    void oneAutoSideKeepsTheRatio() {
        canvas.imageSizes.put("a.png", new float[] {16, 8});
        RecordingCanvas c = paint(layer(new Image.Url("a.png"), null, Length.px(32), Length.AUTO, Length.ZERO, Length.ZERO,
                Repeat.NO_REPEAT, Repeat.NO_REPEAT, BackgroundLayer.Box.BORDER_BOX));
        assertArrayEquals(new float[] {0, 0, 32, 16, 0, 0, 1, 1}, c.ops("drawImage").getFirst().args(), 1e-5f);
    }

    @Test
    void spaceSpreadsWholeTiles() {
        canvas.imageSizes.put("a.png", new float[] {16, 16});
        RecordingCanvas c = paint(layer(new Image.Url("a.png"), null, Length.AUTO, Length.AUTO, Length.ZERO, Length.ZERO,
                Repeat.SPACE, Repeat.NO_REPEAT, BackgroundLayer.Box.BORDER_BOX));
        List<RecordingCanvas.Call> draws = c.ops("drawImage");
        assertEquals(2, draws.size());
        assertEquals(0, draws.get(0).x(), 1e-5);
        assertEquals(24, draws.get(1).x(), 1e-5);
    }

    @Test
    void roundRescalesTilesToFit() {
        canvas.imageSizes.put("a.png", new float[] {16, 16});
        RecordingCanvas c = paint(layer(new Image.Url("a.png"), null, Length.AUTO, Length.AUTO, Length.ZERO, Length.ZERO,
                Repeat.ROUND, Repeat.NO_REPEAT, BackgroundLayer.Box.BORDER_BOX));
        // 40 / 16 = 2.5 rounds to 3 tiles of 13.3px
        assertEquals(3, c.ops("drawImage").getFirst().args()[6], 1e-5);
    }

    @Test
    void spritesStretchOverThePaintingAreaByDefault() {
        RecordingCanvas c = paint(BackgroundLayer.simple(new Image.Sprite("minecraft:widget/button")));
        RecordingCanvas.Call sprite = c.ops("drawSprite").getFirst();
        assertEquals("minecraft:widget/button", sprite.text());
        assertArrayEquals(new float[] {0, 0, 40, 20}, sprite.args(), 1e-5f);
        assertNull(sprite.clip());
    }

    @Test
    void sizedSpritesTileAndClip() {
        RecordingCanvas c = paint(sized(new Image.Sprite("x"), 16, 16, Repeat.REPEAT));
        List<RecordingCanvas.Call> sprites = c.ops("drawSprite");
        assertEquals(6, sprites.size());
        for (RecordingCanvas.Call s : sprites) assertArrayEquals(new float[] {0, 0, 40, 20}, s.clip(), 1e-5f);
    }

    @Test
    void linearGradientRunsAlongItsAngle() {
        RecordingCanvas c = paint(BackgroundLayer.simple(new Image.LinearGradient(90, stops(RED, BLUE), false)));
        assertEquals(800, c.quadArea(), 1e-2);
        forEachVertex(c, (x, y, color) -> {
            if (x == 0) assertEquals(RED, color);
            if (x == 40) assertEquals(BLUE, color);
        });
    }

    @Test
    void gradientsFollowTheBorderRadius() {
        style(box).radiusTopLeft = style(box).radiusBottomRight = Length.px(8);
        canvas.devicePixel = 0.25f;
        RecordingCanvas c = paint(BackgroundLayer.simple(new Image.LinearGradient(180, stops(RED, BLUE), false)));
        assertEquals(800 - 2 * (1 - Math.PI / 4) * 64, c.quadArea(), 2);
        float[] radii = {8, 8, 0, 0, 8, 8, 0, 0};
        forEachVertex(c, (x, y, color) -> assertTrue(Shapes.contains(-0.01f, -0.01f, 40.02f, 20.02f, radii, x, y)
                || near(x, y, radii), x + "," + y));
    }

    @Test
    void repeatingGradientsTileTheirStops() {
        List<Image.ColorStop> stops = List.of(new Image.ColorStop(RED, Length.ZERO), new Image.ColorStop(BLUE, Length.px(10)));
        RecordingCanvas c = paint(BackgroundLayer.simple(new Image.LinearGradient(90, stops, true)));
        assertEquals(800, c.quadArea(), 1e-2);
        forEachVertex(c, (x, y, color) -> {
            float phase = x % 10;
            if (phase > 0.01f && phase < 9.99f) assertEquals(QuadBatch.lerpArgb(RED, BLUE, phase / 10), color, "at " + x);
        });
    }

    @Test
    void radialGradientsAreRingsFromTheCentre() {
        RecordingCanvas c = paint(BackgroundLayer.simple(new Image.RadialGradient(true, Length.PERCENT_50, Length.PERCENT_50,
                stops(RED, BLUE))));
        assertEquals(800, c.quadArea(), 1);
        forEachVertex(c, (x, y, color) -> {
            if (x == 20 && y == 10) assertEquals(RED, color);
        });
    }

    @Test
    void sizedGradientsTile() {
        RecordingCanvas c = paint(sized(new Image.LinearGradient(90, stops(RED, BLUE), false), 10, 10, Repeat.REPEAT));
        assertEquals(800, c.quadArea(), 1e-2);
        forEachVertex(c, (x, y, color) -> {
            if (x == 10 || x == 30) assertTrue(color == RED || color == BLUE);
        });
    }

    @Test
    void fadingToTransparentKeepsTheColour() {
        RecordingCanvas c = paint(BackgroundLayer.simple(new Image.LinearGradient(90, stops(RED, 0), false)));
        forEachVertex(c, (x, y, color) -> assertEquals(0xFF0000, color & 0xFFFFFF));
    }

    private static boolean near(float x, float y, float[] radii) {
        // Chords of the clip polygon lie inside the arc; allow the clip polygon's own vertices on the arc.
        return Shapes.contains(-0.3f, -0.3f, 40.6f, 20.6f, Arrays.copyOf(radii, 8), x, y);
    }

    private static List<Image.ColorStop> stops(int... colors) {
        return Arrays.stream(colors).mapToObj(c -> new Image.ColorStop(c, null)).toList();
    }

    interface VertexCheck {
        void check(float x, float y, int color);
    }

    static void forEachVertex(RecordingCanvas c, VertexCheck check) {
        for (RecordingCanvas.Call call : c.ops("fillQuads")) {
            for (int v = 0; v < call.quadColors().length; v++) check.check(call.quads()[2 * v], call.quads()[2 * v + 1], call.quadColors()[v]);
        }
    }
}
