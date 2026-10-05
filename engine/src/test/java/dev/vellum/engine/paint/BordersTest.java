package dev.vellum.engine.paint;

import dev.vellum.engine.layout.Box;
import dev.vellum.engine.style.BorderStyle;
import dev.vellum.engine.style.ComputedStyle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.vellum.engine.paint.TestTree.style;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BordersTest {
    private static final int GREY = 0xFFC6C6C6, WHITE = 0xFFFFFFFF, SHADOW = 0xFF555555, RED = 0xFFFF0000;
    private final TestTree t = new TestTree();
    private final Box box = t.div(0, 0, 40, 20, 0);

    private void border(BorderStyle style, float width, int color) {
        box.borderTop = box.borderRight = box.borderBottom = box.borderLeft = width;
        ComputedStyle s = style(box);
        s.borderTopWidth = s.borderRightWidth = s.borderBottomWidth = s.borderLeftWidth = width;
        s.borderTopStyle = s.borderRightStyle = s.borderBottomStyle = s.borderLeftStyle = style;
        s.borderTopColor = s.borderRightColor = s.borderBottomColor = s.borderLeftColor = color;
    }

    @Test
    void outsetIsTheVanillaBevel() {
        border(BorderStyle.OUTSET, 2, GREY);
        RecordingCanvas.Call ring = t.paint(box).ops("fillBorder").getFirst();
        assertArrayEquals(new int[] {WHITE, SHADOW, SHADOW, WHITE}, ring.colors());
        assertArrayEquals(new float[] {2, 2, 2, 2}, java.util.Arrays.copyOfRange(ring.args(), 12, 16));
    }

    @Test
    void insetIsTheReverse() {
        border(BorderStyle.INSET, 1, GREY);
        assertArrayEquals(new int[] {SHADOW, WHITE, WHITE, SHADOW}, t.paint(box).ops("fillBorder").getFirst().colors());
    }

    @Test
    void grooveIsTwoOppositeBevels() {
        border(BorderStyle.GROOVE, 2, GREY);
        List<RecordingCanvas.Call> rings = t.paint(box).ops("fillBorder");
        assertEquals(2, rings.size());
        assertArrayEquals(new int[] {SHADOW, WHITE, WHITE, SHADOW}, rings.get(0).colors());
        assertArrayEquals(new int[] {WHITE, SHADOW, SHADOW, WHITE}, rings.get(1).colors());
        assertArrayEquals(new float[] {1, 1, 38, 18}, rings.get(1).bounds(), 1e-5f);
    }

    @Test
    void doubleIsTwoLinesWithAGap() {
        border(BorderStyle.DOUBLE, 3, RED);
        List<RecordingCanvas.Call> rings = t.paint(box).ops("fillBorder");
        assertEquals(2, rings.size());
        assertArrayEquals(new float[] {1, 1, 1, 1}, java.util.Arrays.copyOfRange(rings.get(0).args(), 12, 16));
        assertArrayEquals(new float[] {2, 2, 36, 16}, rings.get(1).bounds(), 1e-5f);
        assertArrayEquals(new float[] {1, 1, 1, 1}, java.util.Arrays.copyOfRange(rings.get(1).args(), 12, 16));
    }

    @Test
    void thinDoubleBordersStaySolid() {
        border(BorderStyle.DOUBLE, 2, RED);
        assertEquals(1, t.paint(box).ops("fillBorder").size());
    }

    @Test
    void dashedSidesAreSegments() {
        border(BorderStyle.SOLID, 2, WHITE);
        style(box).borderTopStyle = BorderStyle.DASHED;
        style(box).borderTopColor = RED;
        RecordingCanvas c = t.paint(box);
        Map<Integer, Double> areas = ShapesTest.areaByColor(c);
        double topTrapezoid = (40 + 36) / 2.0 * 2;
        assertTrue(areas.get(RED) > topTrapezoid * 0.3 && areas.get(RED) < topTrapezoid * 0.7, "about half dashes");
        assertEquals(40 * 20 - 36 * 16 - topTrapezoid, areas.get(WHITE), 1e-3, "the solid sides keep their trapezoids");
        c.quadArea(); // winding and finiteness
        long dashes = c.ops("fillQuads").stream().flatMapToInt(call -> java.util.Arrays.stream(call.quadColors()))
                .filter(color -> color == RED).count() / 4;
        assertTrue(dashes >= 4, "four dashes along a 40px side, some split by the corner clip: " + dashes);
    }

    @Test
    void dottedDotsAreSquare() {
        border(BorderStyle.DOTTED, 2, RED);
        RecordingCanvas c = t.paint(box);
        // Every side is dotted: dots of 2×2 (stretched slightly to fit), about half of the ring.
        double ring = 40 * 20 - 36 * 16;
        assertEquals(ring / 2, c.quadArea(), ring * 0.15);
    }

    @Test
    void widthsSnapToDevicePixels() {
        border(BorderStyle.SOLID, 0.3f, RED);
        box.x = 0.2f;
        RecordingCanvas c = new RecordingCanvas();
        c.devicePixel = 0.5f;
        RecordingCanvas.Call ring = t.paint(box, c).ops("fillBorder").getFirst();
        assertArrayEquals(new float[] {0, 0, 40.0f, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0.5f, 0.5f, 0.5f, 0.5f}, ring.args(), 1e-5f);
    }

    @Test
    void outlinesUseTheBorderMachinery() {
        ComputedStyle s = style(box);
        s.outlineStyle = BorderStyle.OUTSET;
        s.outlineWidth = 1;
        s.outlineOffset = 2;
        s.outlineColor = GREY;
        RecordingCanvas.Call ring = t.paint(box).ops("fillBorder").getFirst();
        assertArrayEquals(new float[] {-3, -3, 46, 26}, ring.bounds(), 1e-5f);
        assertArrayEquals(new int[] {WHITE, SHADOW, SHADOW, WHITE}, ring.colors());
    }
}
