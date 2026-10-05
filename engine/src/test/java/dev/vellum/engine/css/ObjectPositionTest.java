package dev.vellum.engine.css;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static dev.vellum.engine.css.StyleEngine.computedValue;
import static dev.vellum.engine.testing.Page.styleOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@code object-position}: a {@code <position>} of one to four values, stored per axis. */
class ObjectPositionTest {
    @Test
    void oneToFourValues() {
        assertPosition("50%", "50%", "center");
        assertPosition("0px", "50%", "left");
        assertPosition("50%", "0px", "top");
        assertPosition("100%", "100%", "right bottom");
        assertPosition("100%", "0px", "top right");
        assertPosition("10px", "50%", "10px");
        assertPosition("25%", "4px", "25% 4px");
        assertPosition("0px", "40%", "left 40%");
        assertPosition("calc(100% - 4px)", "0px", "right 4px top");
        assertPosition("50%", "90%", "center bottom 10%");
        assertPosition("5px", "calc(100% - 2px)", "left 5px bottom 2px");
        assertPosition("calc(100% - 3px)", "6px", "top 6px right 3px");
        assertPosition("calc(50% + 2px)", "50%", "calc(50% + 2px) center");
        assertPosition("-4px", "50%", "-4px");
    }

    @Test
    void unsetUntilARuleSetsIt() {
        ComputedStyle unset = styleOf("color: red");
        assertSame(Length.AUTO, unset.objectPositionX);
        assertSame(Length.AUTO, unset.objectPositionY);
        assertEquals("50% 50%", computedValue(unset, "object-position"), "serialised as CSS's initial value");
        assertEquals(12, unset.objectX(24), 1e-6, "content is centred");
        assertEquals(-3, unset.objectY(-6), 1e-6, "also when it overflows");
        assertSame(Length.AUTO, styleOf("object-position: 10px; object-position: initial").objectPositionX);
        assertEquals(Length.PERCENT_50, styleOf("object-position: center").objectPositionY, "set, even to the centre");
    }

    @Test
    void offsetsResolveAgainstTheFreeSpace() {
        ComputedStyle s = styleOf("object-position: right 4px bottom 25%");
        assertEquals(16, s.objectX(20), 1e-5);
        assertEquals(30, s.objectY(40), 1e-5);
        assertEquals(-14, s.objectX(-10), 1e-5, "content wider than its box moves the other way");
    }

    @Test
    void invalidValuesAreIgnored() {
        for (String bad : new String[] {"1px 2px 3px", "left right", "top bottom", "50% top 2px 3px 4px", "auto", "red", "2"}) {
            assertSame(Length.AUTO, styleOf("object-position: " + bad).objectPositionX, bad);
        }
    }

    @Test
    void notInherited() {
        Page page = new TestHost().load("<div style='object-position: left top'><p id=p></p></div>");
        assertSame(Length.AUTO, page.style("#p").objectPositionX);
    }

    @Test
    void transitionsWithoutRelayoutAndFromTheCentre() {
        Page page = new TestHost().load("<style>#m { transition: object-position 100ms linear } "
                + "#m.on { object-position: 0 100% }</style><div id=m></div>");
        Element m = page.byId("m");
        m.addClass("on");
        page.frame(0);
        assertFalse(page.frameLaysOut(50), "paint-only");
        assertEquals(25, m.style.objectX(100), 1e-3, "unset animates from 50%");
        assertEquals(75, m.style.objectY(100), 1e-3);
        page.frame(100);
        assertEquals(Length.ZERO, m.style.objectPositionX);
        assertEquals(Length.PERCENT_100, m.style.objectPositionY);
    }

    private static void assertPosition(String x, String y, String css) {
        ComputedStyle s = styleOf("object-position: " + css);
        assertEquals(x, computedValue(s, "-vellum-object-position-x"), css);
        assertEquals(y, computedValue(s, "-vellum-object-position-y"), css);
        assertEquals(x + " " + y, computedValue(s, "object-position"), css);
    }
}
