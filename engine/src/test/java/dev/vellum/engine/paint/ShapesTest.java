package dev.vellum.engine.paint;

import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.testing.RecordingCanvas;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapesTest {
    private static final int RED = 0xFFFF0000, GREEN = 0xFF00FF00, BLUE = 0xFF0000FF, WHITE = 0xFFFFFFFF;

    private static float[] uniform(float r) {
        float[] radii = new float[8];
        Arrays.fill(radii, r);
        return radii;
    }

    @Test
    void radiiResolvePercentagesPerAxis() {
        ComputedStyle s = new ComputedStyle();
        s.radiusTopLeft = s.radiusTopRight = s.radiusBottomRight = s.radiusBottomLeft = Length.percent(50);
        float[] r = new float[8];
        assertTrue(Shapes.radii(s, 40, 20, r));
        assertArrayEquals(new float[] {20, 10, 20, 10, 20, 10, 20, 10}, r);
    }

    @Test
    void radiiScaleDownWhenAdjacentOnesOverlap() {
        ComputedStyle s = new ComputedStyle();
        s.radiusTopLeft = Length.px(30);
        s.radiusTopRight = Length.px(30);
        float[] r = new float[8];
        Shapes.radii(s, 40, 100, r);
        // top: 30 + 30 > 40, so everything scales by 40/60
        assertEquals(20, r[0], 1e-4);
        assertEquals(20, r[1], 1e-4);
        assertEquals(20, r[2], 1e-4);
        assertEquals(0, r[4]);
    }

    @Test
    void noRadiusIsSquare() {
        float[] r = uniform(3);
        assertFalse(Shapes.radii(new ComputedStyle(), 10, 10, r));
        assertArrayEquals(new float[8], r);
    }

    @Test
    void insetRadiiShrinkAndKeepSquareCornersSquare() {
        float[] r = {4, 4, 0, 0, 10, 10, 1, 1};
        float[] out = new float[8];
        Shapes.insetRadii(r, 2, 2, 2, 2, out);
        assertArrayEquals(new float[] {2, 2, 0, 0, 8, 8, 0, 0}, out);
        Shapes.insetRadii(r, -3, -3, -3, -3, out);
        assertArrayEquals(new float[] {7, 7, 0, 0, 13, 13, 4, 4}, out);
    }

    @Test
    void segmentsFollowTheDeviceRadius() {
        assertEquals(0, Shapes.segments(0, 1));
        assertEquals(2, Shapes.segments(1, 1));
        assertEquals(10, Shapes.segments(10, 0.5f));
        assertEquals(16, Shapes.segments(100, 0.25f));
    }

    @Test
    void squareRoundedRectIsAPlainFill() {
        RecordingCanvas c = new RecordingCanvas();
        Shapes.fillRoundedRect(c, 1, 2, 3, 4, new float[8], RED);
        assertEquals(1, c.ops("fillRect").size());
        assertEquals(0, c.quadCount());
    }

    @Test
    void roundedRectCoversItsArea() {
        RecordingCanvas c = new RecordingCanvas();
        c.devicePixel = 0.5f;
        Shapes.fillRoundedRect(c, 10, 10, 40, 20, uniform(5), RED);
        int n = Shapes.segments(5, 0.5f);
        assertEquals(2 * n + 1, c.quadCount(), "a fan over a convex polygon of 4(n+1) vertices");
        double exact = 40 * 20 - (4 - Math.PI) * 25;
        assertEquals(exact, c.quadArea(), exact * 0.01);
        RecordingCanvas.Call call = c.ops("fillQuads").getFirst();
        assertTrue(Arrays.stream(call.quadColors()).allMatch(col -> col == RED));
        assertEquals(10, call.x(), 1e-4);
        assertEquals(40, call.w(), 1e-4);
    }

    @Test
    void squareBorderIsFourTrapezoids() {
        RecordingCanvas c = new RecordingCanvas();
        Shapes.fillBorder(c, new float[] {0, 0, 20, 10}, new float[8], new float[] {1, 2, 3, 4}, new int[] {RED, GREEN, BLUE, WHITE});
        assertEquals(4, c.quadCount());
        assertEquals(20 * 10 - 14 * 6, c.quadArea(), 1e-3);
        Map<Integer, Double> areas = c.quadAreaByColor();
        // top trapezoid: outer 20 wide, inner 14 wide, 1 tall
        assertEquals((20 + 14) / 2.0 * 1, areas.get(RED), 1e-3);
        assertEquals((10 + 6) / 2.0 * 2, areas.get(GREEN), 1e-3);
        assertEquals((20 + 14) / 2.0 * 3, areas.get(BLUE), 1e-3);
        assertEquals((10 + 6) / 2.0 * 4, areas.get(WHITE), 1e-3);
    }

    @Test
    void roundedBorderIsTheRingBetweenTwoRoundedRects() {
        RecordingCanvas c = new RecordingCanvas();
        c.devicePixel = 0.25f;
        Shapes.fillBorder(c, new float[] {0, 0, 40, 40}, uniform(8), new float[] {2, 2, 2, 2}, new int[] {RED, GREEN, BLUE, WHITE});
        double outer = 40 * 40 - (4 - Math.PI) * 64, inner = 36 * 36 - (4 - Math.PI) * 36;
        assertEquals(outer - inner, c.quadArea(), (outer - inner) * 0.01);
        // Equal widths on a square: each side owns exactly a quarter, corners split on the diagonal.
        Map<Integer, Double> areas = c.quadAreaByColor();
        for (int color : new int[] {RED, GREEN, BLUE, WHITE}) assertEquals(c.quadArea() / 4, areas.get(color), 1e-2);
    }

    @Test
    void cornerSplitFollowsTheWidths() {
        RecordingCanvas c = new RecordingCanvas();
        c.devicePixel = 0.25f;
        // Only the top has width: the top-left corner belongs to it entirely.
        Shapes.fillBorder(c, new float[] {0, 0, 40, 40}, uniform(8), new float[] {4, 0, 0, 0}, new int[] {RED, GREEN, BLUE, WHITE});
        assertEquals(Map.of(RED, c.quadAreaByColor().get(RED)), c.quadAreaByColor());
    }

    @Test
    void thickBordersMeetInTheMiddle() {
        RecordingCanvas c = new RecordingCanvas();
        Shapes.fillBorder(c, new float[] {0, 0, 10, 10}, new float[8], new float[] {8, 8, 8, 8}, new int[] {RED, RED, RED, RED});
        assertEquals(100, c.quadArea(), 1e-3);
    }

    @Test
    void transparentSidesAreSkipped() {
        RecordingCanvas c = new RecordingCanvas();
        Shapes.fillBorder(c, new float[] {0, 0, 10, 10}, new float[8], new float[] {1, 1, 1, 1}, new int[] {RED, 0, 0, 0});
        assertEquals(1, c.quadCount());
    }

    @Test
    void containsHonoursRoundedCorners() {
        float[] r = uniform(10);
        assertTrue(Shapes.contains(0, 0, 40, 40, r, 20, 20));
        assertFalse(Shapes.contains(0, 0, 40, 40, r, 1, 1));
        assertTrue(Shapes.contains(0, 0, 40, 40, r, 4, 4));
        assertFalse(Shapes.contains(0, 0, 40, 40, r, 39, 39.5f));
        assertTrue(Shapes.contains(0, 0, 40, 40, null, 0, 0));
        assertFalse(Shapes.contains(0, 0, 40, 40, null, 40, 0), "right edge is exclusive");
    }

    @Test
    void sideQuadsSitBetweenTheArcs() {
        float[] q = new float[8];
        Shapes.sideQuad(Shapes.TOP, 0, 0, 40, 20, uniform(5), new float[] {2, 2, 2, 2}, q);
        assertArrayEquals(new float[] {35, 0, 5, 0, 5, 2, 35, 2}, q);
    }
}
