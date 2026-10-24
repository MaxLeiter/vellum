package dev.vellum.engine.paint;

import dev.vellum.engine.style.Shadow;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the shape shader draws ({@link SmoothShape#coverage} is a port of sdf_box.fsh), and what it leaves to quads. */
class SmoothShapeTest {
    private static float[] radii(float r) {
        float[] out = new float[8];
        Arrays.fill(out, r);
        return out;
    }

    @Test
    void aFilledBoxCoversItsInsideAndHalfOfItsEdge() {
        SmoothShape s = SmoothShape.box(0, 0, 40, 20, radii(8), 0, 0xFFFFFFFF);
        assertNotNull(s);
        assertEquals(1, s.coverage(0, 0, 1), 1e-4, "the centre");
        assertEquals(0.5, s.coverage(20, 0, 1), 1e-4, "on the right edge");
        assertEquals(0, s.coverage(21, 0, 1), 1e-4, "a pixel outside");
        assertEquals(0, s.coverage(19.5f, 9.5f, 1), 1e-4, "outside the rounded corner, inside the square one");
    }

    @Test
    void aBorderLeavesTheInsideUncovered() {
        SmoothShape s = SmoothShape.border(new float[] {0, 0, 40, 20}, radii(8), new float[] {3, 3, 3, 3},
                new int[] {-1, -1, -1, -1});
        assertNotNull(s);
        assertEquals(0, s.coverage(0, 0, 1), 1e-4);
        assertEquals(1, s.coverage(18.5f, 0, 1), 1e-4, "inside the 3px ring");
    }

    @Test
    void aBlurredShadowFadesOutAcrossTheEdge() {
        SmoothShape s = SmoothShape.shadow(new float[] {0, 0, 40, 40}, radii(4), new Shadow(0, 0, 8, 0, 0xFF000000, false));
        assertNotNull(s);
        assertTrue(s.cutout(), "an outer shadow that is not offset leaves the box uncovered");
        assertEquals(0, s.coverage(0, 0, 1), 1e-3, "under the box");
        float atEdge = s.coverage(21, 0, 1), beyond = s.coverage(28, 0, 1);
        assertTrue(atEdge > 0.3 && atEdge < 0.6, "about half just outside the edge: " + atEdge);
        assertTrue(beyond > 0 && beyond < atEdge, "fading further out: " + beyond);
        assertEquals(0, s.coverage(40, 0, 1), 1e-3, "gone 3 sigma out");
    }

    @Test
    void whatOnlyTheTessellationDraws() {
        float[] elliptical = {8, 4, 8, 4, 8, 4, 8, 4};
        assertNull(SmoothShape.box(0, 0, 10, 10, elliptical, 0, -1), "elliptical corners");
        assertNull(SmoothShape.box(0, 0, 10, 10, radii(0), 0, -1), "square boxes stay exact quads");
        assertNull(SmoothShape.border(new float[] {0, 0, 10, 10}, radii(4), new float[] {1, 2, 1, 1}, new int[] {-1, -1, -1, -1}),
                "borders that differ by side");
        assertNull(SmoothShape.shadow(new float[] {0, 0, 10, 10}, radii(4), new Shadow(1, 1, 2, 0, -1, true)), "offset inset shadows");
        assertSame(SmoothShape.NOTHING, SmoothShape.box(0, 0, 0, 10, radii(4), 0, -1));
    }
}
