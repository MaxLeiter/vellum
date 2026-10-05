package dev.vellum.engine.css;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static dev.vellum.engine.css.StyleEngine.computedValue;
import static dev.vellum.engine.testing.Page.styleOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code -mc-gaze-reach} and {@code -mc-gaze-limit}: how far {@code follow-mouse} turns an entity's head toward the
 * pointer.
 */
class GazePropertiesTest {
    @Test
    void defaultsTurnAsTheInventoryDoes() {
        ComputedStyle s = styleOf("color: red");
        assertEquals(40, s.gazeReach);
        assertTrue(Float.isNaN(s.gazeLimitYaw) && Float.isNaN(s.gazeLimitUp) && Float.isNaN(s.gazeLimitDown));
        assertEquals("40px", computedValue(s, "-mc-gaze-reach"));
        assertEquals("none", computedValue(s, "-mc-gaze-limit"));
        for (float d : new float[] {0, 3, -17, 40, 120, -400, 5000}) {
            // Vanilla: the body turns atan(d / 40) × 20 and the head as much again.
            float lean = (float) Math.atan(d / 40.0F) * 20.0F;
            assertEquals(2 * lean, s.gazeYaw(d), d + " px");
            assertEquals(2 * lean, s.gazePitch(d), d + " px");
        }
        assertEquals(20 * Math.PI, s.gazeYaw(1e9f), 1e-4, "about 63° at most");
    }

    @Test
    void reachIsANonNegativeLength() {
        assertEquals(80, styleOf("-mc-gaze-reach: 80px").gazeReach);
        assertEquals("80px", computedValue(styleOf("-mc-gaze-reach: 80px"), "-mc-gaze-reach"));
        assertEquals(16, styleOf("-mc-gaze-reach: 2em").gazeReach, "em resolves against the font size");
        assertEquals(30, styleOf("-mc-gaze-reach: calc(20px + 10px)").gazeReach);
        for (String bad : new String[] {"-4px", "50%", "3", "none", "10deg"}) {
            assertEquals(40, styleOf("-mc-gaze-reach: " + bad).gazeReach, bad);
        }
        ComputedStyle snap = styleOf("-mc-gaze-reach: 0");
        assertEquals(0, snap.gazeReach);
        assertEquals(0, snap.gazeYaw(0), "no NaN at the eyes");
        assertEquals(20 * Math.PI, snap.gazeYaw(1), 1e-4, "any offset turns all the way");
    }

    @Test
    void aLongerReachTurnsLess() {
        ComputedStyle near = styleOf("color: red"), far = styleOf("-mc-gaze-reach: 80px");
        assertTrue(far.gazeYaw(40) < near.gazeYaw(40));
        assertEquals(near.gazeYaw(40), far.gazeYaw(80), 1e-5, "the same turn twice as far away");
    }

    @Test
    void limitTakesOneToThreeValues() {
        assertLimit("30deg", 30, 30, 30, "30deg");
        assertLimit("30deg 9deg", 30, 9, 9, "30deg 9deg");
        assertLimit("30deg 12deg 4deg", 30, 12, 4, "30deg 12deg 4deg");
        assertLimit("30deg 30deg 9deg", 30, 30, 9, "30deg 30deg 9deg");
        assertLimit("9deg 9deg 9deg", 9, 9, 9, "9deg");
        assertLimit(".1turn 0 calc(5deg + 1deg)", 36, 0, 6, "36deg 0deg 6deg");
        assertLimit("none 9deg", Float.NaN, 9, 9, "none 9deg");
        assertLimit("20deg none 5deg", 20, Float.NaN, 5, "20deg none 5deg");
        assertLimit("none", Float.NaN, Float.NaN, Float.NaN, "none");
        assertEquals("12deg", computedValue(styleOf("-mc-gaze-limit: 30deg 12deg 4deg"), "-vellum-gaze-limit-up"));
    }

    @Test
    void invalidLimitsAreIgnored() {
        for (String bad : new String[] {"-5deg", "10px", "10", "1deg 2deg 3deg 4deg", "auto", "30deg, 9deg"}) {
            ComputedStyle s = styleOf("-mc-gaze-limit: " + bad);
            assertTrue(Float.isNaN(s.gazeLimitYaw) && Float.isNaN(s.gazeLimitDown), bad);
        }
        assertEquals(20, styleOf("-mc-gaze-limit: 20deg; -mc-gaze-limit: 5deg -1deg").gazeLimitDown);
    }

    @Test
    void limitsCapTheTurnEachWay() {
        ComputedStyle s = styleOf("-mc-gaze-reach: 80px; -mc-gaze-limit: 30deg 12deg 4deg");
        assertEquals(30, s.gazeYaw(1000));
        assertEquals(-30, s.gazeYaw(-1000));
        assertEquals(12, s.gazePitch(120), "up");
        assertEquals(-4, s.gazePitch(-120), "a pointer far below barely bows the head");
        float small = (float) Math.atan(1 / 80.0F) * 40.0F;
        assertEquals(small, s.gazeYaw(1), "within the limits nothing changes");
        assertEquals(-small, s.gazePitch(-1));
        ComputedStyle still = styleOf("-mc-gaze-limit: 0 none");
        assertEquals(0, still.gazeYaw(200), "zero holds that axis still");
        assertEquals(still.gazePitch(-200), styleOf("color: red").gazePitch(-200), "none leaves it be");
    }

    @Test
    void notInherited() {
        Page page = new TestHost().load("<div style='-mc-gaze-reach: 80px; -mc-gaze-limit: 9deg'><p id=p></p></div>");
        assertEquals(40, page.style("#p").gazeReach);
        assertTrue(Float.isNaN(page.style("#p").gazeLimitYaw));
    }

    @Test
    void transitionsWithoutRelayout() {
        Page page = new TestHost().load("<style>#m { transition: -mc-gaze-reach 100ms linear, -mc-gaze-limit 100ms linear;"
                + " -mc-gaze-limit: 60deg 20deg } #m.on { -mc-gaze-reach: 80px; -mc-gaze-limit: 30deg 10deg 4deg }</style>"
                + "<div id=m></div>");
        Element m = page.byId("m");
        m.addClass("on");
        page.frame(0);
        assertFalse(page.frameLaysOut(50), "paint-only");
        assertEquals(60, m.style.gazeReach, 1e-3);
        assertEquals(45, m.style.gazeLimitYaw, 1e-3);
        assertEquals(15, m.style.gazeLimitUp, 1e-3);
        assertEquals(12, m.style.gazeLimitDown, 1e-3);
        page.frame(100);
        assertEquals("30deg 10deg 4deg", computedValue(m.style, "-mc-gaze-limit"));
    }

    @Test
    void noneDoesNotInterpolate() {
        Page page = new TestHost().load("<style>#m { transition: -mc-gaze-limit 100ms linear } #m.on { -mc-gaze-limit: 30deg }"
                + " @keyframes open { from { -mc-gaze-limit: 10deg } to { -mc-gaze-limit: none } }"
                + " #k { animation: open 100ms linear both }</style><div id=m></div><div id=k></div>");
        Element m = page.byId("m"), k = page.byId("k");
        m.addClass("on");
        page.frame(0);
        page.frame(25);
        assertEquals(30, m.style.gazeLimitYaw, "no transition from none: it applies at once");
        assertEquals(10, k.style.gazeLimitYaw, "keyframes flip at the midpoint");
        page.frame(75);
        assertTrue(Float.isNaN(k.style.gazeLimitYaw));
    }

    private static void assertLimit(String css, float yaw, float up, float down, String serialised) {
        ComputedStyle s = styleOf("-mc-gaze-limit: " + css);
        assertEquals(yaw, s.gazeLimitYaw, css);
        assertEquals(up, s.gazeLimitUp, css);
        assertEquals(down, s.gazeLimitDown, css);
        assertEquals(serialised, computedValue(s, "-mc-gaze-limit"), css);
    }
}
