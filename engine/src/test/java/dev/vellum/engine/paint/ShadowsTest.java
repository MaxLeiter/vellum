package dev.vellum.engine.paint;

import dev.vellum.engine.style.Colors;
import dev.vellum.engine.testing.RecordingCanvas;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Box shadows of a 40x20 box at (20, 20), from CSS. */
class ShadowsTest {
    private static RecordingCanvas paint(String css) {
        return new TestHost().load("<div style='position: absolute; left: 20px; top: 20px; width: 40px; height: 20px; "
                + css + "'></div>").paint();
    }

    @Test
    void unoffsetOuterShadowsLeaveTheBoxUncovered() {
        RecordingCanvas c = paint("box-shadow: 0 0 4px black");
        assertTrue(c.quadCount() > 0);
        c.forEachVertex((x, y, color) -> {
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
        RecordingCanvas c = paint("box-shadow: 0 0 8px black");
        // Along the line y = 30 to the right of the box, alpha must not increase with distance.
        TreeMap<Float, Integer> alphaByX = new TreeMap<>();
        c.forEachVertex((x, y, color) -> {
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
        assertEquals(44 * 24 - 40 * 20, paint("box-shadow: 0 0 0 2px black").quadArea(), 1e-3);
    }

    @Test
    void offsetShadowsIncludeTheirCore() {
        RecordingCanvas c = paint("box-shadow: 5px 5px black");
        assertEquals(1, c.ops("fillQuads").size());
        assertEquals(40 * 20, c.quadArea(), 1e-3);
        assertEquals(25, c.ops("fillQuads").getFirst().x(), 1e-4);
    }

    @Test
    void transparentShadowsDrawNothing() {
        assertEquals(0, paint("box-shadow: 0 0 4px 4px transparent").quadCount());
    }

    @Test
    void shadowsOfAnEmptyShapeDrawNothing() {
        assertEquals(0, paint("box-shadow: 0 0 4px -30px black").quadCount());
    }

    @Test
    void insetShadowsStayInsideThePaddingBox() {
        RecordingCanvas c = paint("border: 2px solid transparent; box-shadow: inset 0 3px 2px black");
        assertTrue(c.quadCount() > 0);
        c.forEachVertex((x, y, color) -> {
            assertTrue(x >= 21.99f && x <= 58.01f && y >= 21.99f && y <= 38.01f, "outside the padding box: " + x + "," + y);
            if (y <= 22.01f) assertEquals(255, Colors.alpha(color), "offset 3 beyond a blur of 2: the top edge is in full shadow");
        });
        c.quadArea();
    }

    @Test
    void insetShadowOfAShrunkenShapeFillsThePaddingBox() {
        assertEquals(40 * 20, paint("box-shadow: inset 0 0 0 30px black").quadArea(), 1e-3);
    }

    @Test
    void outerShadowsGoUnderTheBackgroundAndInsetOnesOver() {
        RecordingCanvas c = paint("background: #123456; border-top: 1px solid; box-shadow: inset 0 0 2px black, 1px 1px 2px black");
        assertEquals(List.of("fillQuads", "fillRect", "fillQuads", "fillBorder"), c.calls.stream().map(RecordingCanvas.Call::op).toList());
    }
}
