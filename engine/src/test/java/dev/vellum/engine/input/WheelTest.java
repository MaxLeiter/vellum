package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.WheelEvent;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.vellum.engine.testing.Page.NONE;
import static dev.vellum.engine.testing.Page.SHIFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WheelTest {
    private static final String INNER = "overflow: auto; width: 100px; height: 50px; scroll-behavior: auto";

    /** outer (100x100, content 300 tall) scrolls; inner (100x50 inside it, content 200x100) scrolls both ways. */
    private final Page page = new TestHost().load("""
            <div id=outer style="overflow: auto; width: 100px; height: 100px; scroll-behavior: auto">
              <div id=inner style="%s"><p id=content style="margin: 0; width: 200px; height: 100px">x</p></div>
              <div style="height: 250px"></div>
            </div>""".formatted(INNER));
    private final Element outer = page.byId("outer"), inner = page.byId("inner");

    private boolean wheel(float dx, float dy) {
        return page.wheel(10, 10, dx, dy);
    }

    @Test
    void scrollsTheInnermostContainerThenChainsOutward() {
        assertTrue(wheel(0, 30));
        assertEquals(30, inner.scrollTop());
        wheel(0, 30);
        assertEquals(50, inner.scrollTop(), "clamped at its end");
        assertEquals(0, outer.scrollTop());
        wheel(0, 30);
        assertEquals(30, outer.scrollTop(), "inner cannot move further down: chain");
        wheel(0, -10);
        assertEquals(40, inner.scrollTop(), "inner can move up again");
        assertEquals(30, outer.scrollTop());
    }

    @Test
    void shiftWheelScrollsHorizontally() {
        page.input.wheel(10, 10, 0, 20, SHIFT);
        assertEquals(20, inner.scrollLeft());
        assertEquals(0, inner.scrollTop());
    }

    @Test
    void wheelEventCanCancelScrolling() {
        List<Float> deltas = new ArrayList<>();
        page.byId("content").addEventListener("wheel", e -> {
            deltas.add(((WheelEvent) e).deltaY);
            e.preventDefault();
        });
        assertTrue(wheel(0, 30));
        assertEquals(List.of(30f), deltas);
        assertEquals(0, inner.scrollTop());
    }

    @Test
    void overflowHiddenIsNotUserScrollable() {
        inner.setAttribute("style", INNER + "; overflow-y: hidden");
        page.frame();
        wheel(0, 30);
        assertEquals(0, inner.scrollTop());
        assertEquals(30, outer.scrollTop());
    }

    @Test
    void nothingToScrollIsNotConsumed() {
        outer.scrollTo(0, 200);
        inner.scrollTo(0, 50);
        assertFalse(wheel(0, 10));
        assertFalse(page.input.wheel(500, 500, 0, 10, NONE), "outside the document");
    }

    @Test
    void smoothScrollingEasesTowardAnAccumulatedTarget() {
        inner.setAttribute("style", INNER + "; scroll-behavior: smooth");
        page.frame(0);
        List<String> scrolls = new ArrayList<>();
        inner.addEventListener("scroll", (Event e) -> scrolls.add("scroll"));
        wheel(0, 20);
        wheel(0, 20);
        assertEquals(0, inner.scrollTop(), "nothing moves until the next frame");
        float last = 0;
        for (int t = 16; t <= 96; t += 16) {
            page.frame(t);
            assertTrue(inner.scrollTop() > last && inner.scrollTop() < 40, "eases in steps toward 40: " + inner.scrollTop());
            last = inner.scrollTop();
        }
        assertTrue(last > 35, "mostly there after ~100 ms: " + last);
        for (int t = 112; t <= 400; t += 16) page.frame(t);
        assertEquals(40, inner.scrollTop());
        assertFalse(scrolls.isEmpty());
        wheel(0, 100);
        wheel(0, 100);
        for (int t = 416; t <= 1000; t += 16) page.frame(t);
        assertEquals(50, inner.scrollTop(), "the target is clamped to the scroll range");
    }
}
