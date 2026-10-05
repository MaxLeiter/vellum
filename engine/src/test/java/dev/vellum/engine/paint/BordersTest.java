package dev.vellum.engine.paint;

import dev.vellum.engine.testing.RecordingCanvas;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Borders and outlines of a 40x20 box at the top left, from CSS. */
class BordersTest {
    private static final int GREY = 0xFFC6C6C6, WHITE = 0xFFFFFFFF, SHADOW = 0xFF555555, RED = 0xFFFF0000;

    private static RecordingCanvas paint(String css) {
        return paint(css, new RecordingCanvas());
    }

    private static RecordingCanvas paint(String css, RecordingCanvas canvas) {
        return new TestHost().load("<div style='position: absolute; width: 40px; height: 20px; " + css + "'></div>").paint(canvas);
    }

    private static List<RecordingCanvas.Call> rings(String css) {
        return paint(css).ops("fillBorder");
    }

    private static float[] widths(RecordingCanvas.Call ring) {
        return Arrays.copyOfRange(ring.args(), 12, 16);
    }

    @Test
    void outsetIsTheVanillaBevel() {
        RecordingCanvas.Call ring = rings("border: 2px outset #c6c6c6").getFirst();
        assertArrayEquals(new int[] {WHITE, SHADOW, SHADOW, WHITE}, ring.colors());
        assertArrayEquals(new float[] {2, 2, 2, 2}, widths(ring));
    }

    @Test
    void insetIsTheReverse() {
        assertArrayEquals(new int[] {SHADOW, WHITE, WHITE, SHADOW}, rings("border: 1px inset #c6c6c6").getFirst().colors());
    }

    @Test
    void grooveIsTwoOppositeBevels() {
        List<RecordingCanvas.Call> rings = rings("border: 2px groove #c6c6c6");
        assertEquals(2, rings.size());
        assertArrayEquals(new int[] {SHADOW, WHITE, WHITE, SHADOW}, rings.get(0).colors());
        assertArrayEquals(new int[] {WHITE, SHADOW, SHADOW, WHITE}, rings.get(1).colors());
        assertArrayEquals(new float[] {1, 1, 38, 18}, rings.get(1).bounds(), 1e-5f);
    }

    @Test
    void doubleIsTwoLinesWithAGap() {
        List<RecordingCanvas.Call> rings = rings("border: 3px double red");
        assertEquals(2, rings.size());
        assertArrayEquals(new float[] {1, 1, 1, 1}, widths(rings.get(0)));
        assertArrayEquals(new float[] {2, 2, 36, 16}, rings.get(1).bounds(), 1e-5f);
        assertArrayEquals(new float[] {1, 1, 1, 1}, widths(rings.get(1)));
    }

    @Test
    void thinDoubleBordersStaySolid() {
        assertEquals(1, rings("border: 2px double red").size());
    }

    @Test
    void dashedSidesAreSegments() {
        RecordingCanvas c = paint("border: 2px solid white; border-top: 2px dashed red");
        Map<Integer, Double> areas = c.quadAreaByColor();
        double topTrapezoid = (40 + 36) / 2.0 * 2;
        assertTrue(areas.get(RED) > topTrapezoid * 0.3 && areas.get(RED) < topTrapezoid * 0.7, "about half dashes");
        assertEquals(40 * 20 - 36 * 16 - topTrapezoid, areas.get(WHITE), 1e-3, "the solid sides keep their trapezoids");
        c.quadArea(); // winding and finiteness
        long dashes = c.ops("fillQuads").stream().flatMapToInt(call -> Arrays.stream(call.quadColors()))
                .filter(color -> color == RED).count() / 4;
        assertTrue(dashes >= 4, "four dashes along a 40px side, some split by the corner clip: " + dashes);
    }

    @Test
    void dottedDotsAreSquare() {
        // Every side is dotted: dots of 2×2 (stretched slightly to fit), about half of the ring.
        double ring = 40 * 20 - 36 * 16;
        assertEquals(ring / 2, paint("border: 2px dotted red").quadArea(), ring * 0.15);
    }

    @Test
    void widthsSnapToDevicePixels() {
        RecordingCanvas canvas = new RecordingCanvas();
        canvas.devicePixel = 0.5f;
        RecordingCanvas.Call ring = paint("left: 0.2px; border: 0.3px solid red", canvas).ops("fillBorder").getFirst();
        assertArrayEquals(new float[] {0, 0, 40.0f, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0.5f, 0.5f, 0.5f, 0.5f}, ring.args(), 1e-5f);
    }

    @Test
    void outlinesUseTheBorderMachinery() {
        RecordingCanvas.Call ring = rings("outline: 1px outset #c6c6c6; outline-offset: 2px").getFirst();
        assertArrayEquals(new float[] {-3, -3, 46, 26}, ring.bounds(), 1e-5f);
        assertArrayEquals(new int[] {WHITE, SHADOW, SHADOW, WHITE}, ring.colors());
    }
}
