package dev.vellum.engine.css;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.EntityFocus;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static dev.vellum.engine.css.StyleEngine.computedValue;
import static dev.vellum.engine.testing.Page.styleOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code -mc-yaw}, {@code -mc-pitch}, {@code -mc-model-scale} and {@code -mc-entity-focus}: how 3D content is turned,
 * viewed, sized and framed.
 */
class ModelPropertiesTest {
    @Test
    void anglesInAnyUnitComputeToDegrees() {
        ComputedStyle s = styleOf("-mc-yaw: .5turn; -mc-pitch: calc(10deg + 5deg); -mc-model-scale: 1.5");
        assertEquals(180, s.modelYaw);
        assertEquals(15, s.modelPitch);
        assertEquals("180deg", computedValue(s, "-mc-yaw"));
        assertEquals("15deg", computedValue(s, "-mc-pitch"));
        assertEquals("1.5", computedValue(s, "-mc-model-scale"));
        assertEquals(-90, styleOf("-mc-yaw: -1.5708rad").modelYaw, 1e-3);
        ComputedStyle initial = styleOf("color: red");
        assertEquals("0deg", computedValue(initial, "-mc-yaw"));
        assertEquals("1", computedValue(initial, "-mc-model-scale"));
        assertEquals(1, styleOf("-mc-model-scale: -2").modelScale, "negative scales are invalid");
        assertEquals(0, styleOf("-mc-yaw: 30px").modelYaw, "lengths are not angles");
    }

    @Test
    void entityFocusIsAKeyword() {
        assertEquals(EntityFocus.BODY, styleOf("color: red").entityFocus);
        assertEquals("body", computedValue(styleOf("color: red"), "-mc-entity-focus"));
        ComputedStyle eyes = styleOf("-mc-entity-focus: EYES");
        assertEquals(EntityFocus.EYES, eyes.entityFocus);
        assertEquals("eyes", computedValue(eyes, "-mc-entity-focus"));
        assertEquals(EntityFocus.BODY, styleOf("-mc-entity-focus: head").entityFocus, "unknown keywords are invalid");
        assertEquals(EntityFocus.EYES, styleOf("-mc-entity-focus: eyes; -mc-entity-focus: 1").entityFocus);
    }

    @Test
    void notInherited() {
        Page page = new TestHost().load("<div style='-mc-yaw: 90deg; -mc-entity-focus: eyes'><p id=p></p></div>");
        assertEquals(0, page.style("#p").modelYaw);
        assertEquals(EntityFocus.BODY, page.style("#p").entityFocus);
    }

    @Test
    void focusChangesWithoutRelayout() {
        Page page = new TestHost().load("<style>#m.on { -mc-entity-focus: eyes }</style><div id=m></div>");
        page.byId("m").addClass("on");
        assertFalse(page.frameLaysOut(16), "paint-only");
        assertEquals(EntityFocus.EYES, page.byId("m").style.entityFocus);
    }

    @Test
    void transitionsTurnWithoutRelayout() {
        Page page = new TestHost().load("<style>#m { transition: -mc-yaw 100ms linear } #m.on { -mc-yaw: 180deg }</style>"
                + "<div id=m></div>");
        Element m = page.byId("m");
        m.addClass("on");
        page.frame(0);
        assertFalse(page.frameLaysOut(50), "paint-only");
        assertEquals(90, m.style.modelYaw, 1e-3);
        page.frame(100);
        assertEquals(180, m.style.modelYaw, 1e-3);
    }

    @Test
    void keyframesSpinPastAFullTurn() {
        Page page = new TestHost().load("<style>@keyframes spin { to { -mc-yaw: 360deg } }"
                + "#m { animation: spin 1s linear infinite }</style><div id=m></div>");
        Element m = page.byId("m");
        page.frame(250);
        assertEquals(90, m.style.modelYaw, 1e-3);
        page.frame(1750);
        assertEquals(270, m.style.modelYaw, 1e-3);
    }
}
