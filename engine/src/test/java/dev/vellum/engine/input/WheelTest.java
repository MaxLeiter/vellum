package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.WheelEvent;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.style.Overflow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.vellum.engine.input.Fixture.NONE;
import static dev.vellum.engine.input.Fixture.SHIFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WheelTest {
    /** outer (100x100, content 300 tall) scrolls; inner (100x50 inside it, content 200x100) scrolls both ways. */
    private final Fixture fx = new Fixture("<div id=outer><div id=inner><p id=content>x</p></div></div>");
    private final Element outer = fx.el("outer"), inner = fx.el("inner");

    {
        Box o = fx.box(outer, null, 0, 0, 100, 100);
        o.scrollHeight = 300;
        Box i = fx.box(inner, o, 0, 0, 100, 50);
        i.scrollWidth = 200;
        i.scrollHeight = 100;
        fx.box("content", i, 0, 0, 200, 100);
        for (Element e : List.of(outer, inner)) {
            e.style.overflowX = Overflow.AUTO;
            e.style.overflowY = Overflow.AUTO;
            e.style.scrollSmooth = false;
        }
    }

    private boolean wheel(float dx, float dy) {
        return fx.input.wheel(10, 10, dx, dy, NONE);
    }

    @Test
    void scrollsTheInnermostContainerThenChainsOutward() {
        assertTrue(wheel(0, 30));
        assertEquals(30, inner.scrollTop);
        wheel(0, 30);
        assertEquals(50, inner.scrollTop, "clamped at its end");
        assertEquals(0, outer.scrollTop);
        wheel(0, 30);
        assertEquals(30, outer.scrollTop, "inner cannot move further down: chain");
        wheel(0, -10);
        assertEquals(40, inner.scrollTop, "inner can move up again");
        assertEquals(30, outer.scrollTop);
    }

    @Test
    void shiftWheelScrollsHorizontally() {
        fx.input.wheel(10, 10, 0, 20, SHIFT);
        assertEquals(20, inner.scrollLeft);
        assertEquals(0, inner.scrollTop);
    }

    @Test
    void wheelEventCanCancelScrolling() {
        List<Float> deltas = new ArrayList<>();
        fx.el("content").addEventListener("wheel", e -> {
            deltas.add(((WheelEvent) e).deltaY);
            e.preventDefault();
        });
        assertTrue(wheel(0, 30));
        assertEquals(List.of(30f), deltas);
        assertEquals(0, inner.scrollTop);
    }

    @Test
    void overflowHiddenIsNotUserScrollable() {
        inner.style.overflowY = Overflow.HIDDEN;
        wheel(0, 30);
        assertEquals(0, inner.scrollTop);
        assertEquals(30, outer.scrollTop);
    }

    @Test
    void nothingToScrollIsNotConsumed() {
        outer.scrollTop = 200;
        inner.scrollTop = 50;
        assertFalse(wheel(0, 10));
        assertFalse(fx.input.wheel(500, 500, 0, 10, NONE), "outside the document");
    }

    @Test
    void smoothScrollingEasesTowardAnAccumulatedTarget() {
        inner.style.scrollSmooth = true;
        List<String> scrolls = new ArrayList<>();
        inner.addEventListener("scroll", (Event e) -> scrolls.add("scroll"));
        fx.input.tick(0);
        wheel(0, 20);
        wheel(0, 20);
        assertEquals(0, inner.scrollTop, "nothing moves until the next tick");
        float last = 0;
        for (int t = 16; t <= 96; t += 16) {
            fx.input.tick(t);
            assertTrue(inner.scrollTop > last && inner.scrollTop < 40, "eases in steps toward 40: " + inner.scrollTop);
            last = inner.scrollTop;
        }
        assertTrue(last > 35, "mostly there after ~100 ms: " + last);
        for (int t = 112; t <= 400; t += 16) fx.input.tick(t);
        assertEquals(40, inner.scrollTop);
        assertFalse(scrolls.isEmpty());
        wheel(0, 100);
        wheel(0, 100);
        for (int t = 416; t <= 1000; t += 16) fx.input.tick(t);
        assertEquals(50, inner.scrollTop, "the target is clamped to the scroll range");
    }
}
