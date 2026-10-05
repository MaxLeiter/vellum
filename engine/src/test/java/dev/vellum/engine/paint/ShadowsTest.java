package dev.vellum.engine.paint;

import dev.vellum.engine.layout.Box;
import dev.vellum.engine.style.BorderStyle;
import dev.vellum.engine.style.Colors;
import dev.vellum.engine.style.Shadow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.vellum.engine.paint.BackgroundsTest.forEachVertex;
import static dev.vellum.engine.paint.TestTree.style;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowsTest {
    private static final int BLACK = 0xFF000000;
    private final TestTree t = new TestTree();
    private final Box root = t.div(0, 0, 100, 100, 0);
    private final Box box = TestTree.add(root, t.div(20, 20, 40, 20, 0));

    private RecordingCanvas paint(Shadow... shadows) {
        style(box).boxShadow = List.of(shadows);
        return t.paint(root);
    }

    @Test
    void unoffsetOuterShadowsLeaveTheBoxUncovered() {
        RecordingCanvas c = paint(new Shadow(0, 0, 4, 0, BLACK, false));
        assertTrue(c.quadCount() > 0);
        forEachVertex(c, (x, y, color) -> {
            assertFalse(x > 20.01f && x < 59.99f && y > 20.01f && y < 39.99f, "vertex inside the box: " + x + "," + y);
            float out = Math.max(Math.max(20 - x, x - 60), Math.max(20 - y, y - 40));
            if (out <= 0.01f && (x == 20 || x == 60) && (y == 20 || y == 40)) {
                assertEquals(0x80, Colors.alpha(color), 2, "half coverage on the shape's edge");
            }
            if (out >= 3.99f) assertEquals(0, Colors.alpha(color), "clear at the blur's outer edge");
        });
        c.quadArea();
    }

    @Test
    void alphaFallsOffOutwards() {
        RecordingCanvas c = paint(new Shadow(0, 0, 8, 0, BLACK, false));
        // Along the line y = 30 to the right of the box, alpha must not increase with distance.
        java.util.TreeMap<Float, Integer> alphaByX = new java.util.TreeMap<>();
        forEachVertex(c, (x, y, color) -> {
            if (Math.abs(y - 30) < 15 && x >= 60) alphaByX.merge(x, Colors.alpha(color), Math::max);
        });
        int previous = 256;
        for (int alpha : alphaByX.values()) {
            assertTrue(alpha <= previous, alphaByX.toString());
            previous = alpha;
        }
        assertTrue(alphaByX.size() >= 3);
    }

    @Test
    void spreadWithoutBlurIsAnExactRing() {
        RecordingCanvas c = paint(new Shadow(0, 0, 0, 2, BLACK, false));
        assertEquals(44 * 24 - 40 * 20, c.quadArea(), 1e-3);
    }

    @Test
    void offsetShadowsIncludeTheirCore() {
        RecordingCanvas c = paint(new Shadow(5, 5, 0, 0, BLACK, false));
        assertEquals(1, c.ops("fillQuads").size());
        assertEquals(40 * 20, c.quadArea(), 1e-3);
        assertEquals(25, c.ops("fillQuads").getFirst().x(), 1e-4);
    }

    @Test
    void transparentShadowsDrawNothing() {
        assertEquals(0, paint(new Shadow(0, 0, 4, 4, 0x00000000, false)).quadCount());
    }

    @Test
    void shadowsOfAnEmptyShapeDrawNothing() {
        assertEquals(0, paint(new Shadow(0, 0, 4, -30, BLACK, false)).quadCount());
    }

    @Test
    void insetShadowsStayInsideThePaddingBox() {
        box.borderTop = box.borderRight = box.borderBottom = box.borderLeft = 2;
        RecordingCanvas c = paint(new Shadow(0, 3, 2, 0, BLACK, true));
        assertTrue(c.quadCount() > 0);
        forEachVertex(c, (x, y, color) -> {
            assertTrue(x >= 21.99f && x <= 58.01f && y >= 21.99f && y <= 38.01f, "outside the padding box: " + x + "," + y);
            if (y <= 22.01f) assertEquals(255, Colors.alpha(color), "offset 3 beyond a blur of 2: the top edge is in full shadow");
        });
        c.quadArea();
    }

    @Test
    void insetShadowOfAShrunkenShapeFillsThePaddingBox() {
        RecordingCanvas c = paint(new Shadow(0, 0, 0, 30, BLACK, true));
        assertEquals(40 * 20, c.quadArea(), 1e-3);
    }

    @Test
    void outerShadowsGoUnderTheBackgroundAndInsetOnesOver() {
        style(box).backgroundColor = 0xFF123456;
        style(box).borderTopStyle = BorderStyle.SOLID;
        style(box).borderTopWidth = box.borderTop = 1;
        RecordingCanvas c = paint(new Shadow(0, 0, 2, 0, BLACK, true), new Shadow(1, 1, 2, 0, BLACK, false));
        List<String> ops = c.calls.stream().map(RecordingCanvas.Call::op).toList();
        assertEquals(List.of("fillQuads", "fillRect", "fillQuads", "fillBorder"), ops);
    }
}
