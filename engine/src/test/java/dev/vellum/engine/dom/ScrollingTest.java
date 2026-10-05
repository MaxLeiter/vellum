package dev.vellum.engine.dom;

import dev.vellum.engine.dom.Element.ScrollAlign;
import dev.vellum.engine.dom.Element.ScrollBehavior;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The element owns its scroll position and smooth-scroll destination; every scroll (input, scripts, focus, layout
 * clamping) goes through it and fires {@code scroll} once per frame.
 */
class ScrollingTest {
    /** A 50px scroller over 200px of content, with "low" at y 120, scroll events logged as their scrollTop. */
    private final Page page = new TestHost().load("""
            <div id=s style="overflow: auto; height: 50px; scroll-behavior: auto">
              <div id=content style="height: 200px; padding-top: 120px"><div id=low style="height: 20px"></div></div>
            </div>""");
    private final Element s = page.byId("s");
    private final List<Float> events = new ArrayList<>();

    {
        s.addEventListener("scroll", e -> events.add(s.scrollTop()));
    }

    @Test
    void scrollFiresOncePerFrameAfterAnyNumberOfMoves() {
        s.scrollTo(0, 10);
        s.scrollTo(0, 20);
        assertEquals(20, s.scrollTop());
        assertEquals(List.of(), events, "not during the call");
        page.frame(16);
        assertEquals(List.of(20f), events);
        page.frame(32);
        assertEquals(List.of(20f), events, "nothing moved");
    }

    @Test
    void positionsClampToTheRangeAndLayoutReclamps() {
        s.scrollTo(0, 500);
        assertEquals(150, s.scrollTop());
        page.frame(16);
        page.byId("content").setAttribute("style", "height: 100px");
        page.frame(32);
        assertEquals(50, s.scrollTop(), "less content: clamped by the relayout");
        page.frame(48);
        assertEquals(List.of(150f, 50f), events, "clamping fires scroll too");
    }

    @Test
    void smoothScrollsEaseAndAnInstantScrollCancelsThem() {
        s.scrollTo(0, 100, ScrollBehavior.SMOOTH);
        assertEquals(0, s.scrollTop());
        page.frame(16);
        float eased = s.scrollTop();
        assertTrue(eased > 0 && eased < 100, "on its way: " + eased);
        s.scrollTo(0, 10);
        for (int t = 32; t < 400; t += 16) page.frame(t);
        assertEquals(10, s.scrollTop(), "the direct scroll replaced the smooth one");
    }

    @Test
    void autoFollowsScrollBehavior() {
        s.scrollTo(0, 40, ScrollBehavior.AUTO);
        assertEquals(40, s.scrollTop(), "scroll-behavior: auto is instant");
        s.setAttribute("style", "overflow: auto; height: 50px; scroll-behavior: smooth");
        page.frame(16);
        s.scrollBy(0, 40, ScrollBehavior.AUTO);
        assertEquals(40, s.scrollTop(), "smooth: nothing moves until the next frame");
        s.scrollBy(0, 40, ScrollBehavior.AUTO);
        for (int t = 32; t < 600; t += 16) page.frame(t);
        assertEquals(120, s.scrollTop(), "deltas add up from the destination");
    }

    @Test
    void scrollIntoViewAligns() {
        Element low = page.byId("low");
        low.scrollIntoView(ScrollAlign.NEAREST, ScrollAlign.NEAREST, ScrollBehavior.INSTANT);
        assertEquals(90, s.scrollTop(), "nearest: the bottom edge comes into view");
        low.scrollIntoView(ScrollAlign.NEAREST, ScrollAlign.NEAREST, ScrollBehavior.INSTANT);
        assertEquals(90, s.scrollTop(), "already in view: no scroll");
        low.scrollIntoView(ScrollAlign.START, ScrollAlign.NEAREST, ScrollBehavior.INSTANT);
        assertEquals(120, s.scrollTop());
        low.scrollIntoView(ScrollAlign.CENTER, ScrollAlign.NEAREST, ScrollBehavior.INSTANT);
        assertEquals(105, s.scrollTop());
        low.scrollIntoView(ScrollAlign.END, ScrollAlign.NEAREST, ScrollBehavior.SMOOTH);
        assertEquals(105, s.scrollTop());
        for (int t = 16; t < 600; t += 16) page.frame(t);
        assertEquals(90, s.scrollTop());
    }

    @Test
    void nestedScrollersBringTheElementIntoViewFromTheInsideOut() {
        Page nested = new TestHost().load("""
                <div id=outer style="overflow: auto; height: 50px">
                  <div style="height: 100px"></div>
                  <div id=inner style="overflow: auto; height: 40px">
                    <div style="height: 100px"></div><div id=target style="height: 10px"></div>
                  </div>
                  <div style="height: 100px"></div>
                </div>""");
        nested.byId("target").scrollIntoView(ScrollAlign.START, ScrollAlign.NEAREST, ScrollBehavior.INSTANT);
        assertEquals(70, nested.byId("inner").scrollTop(), "the most it can: 140 - 40");
        assertEquals(130, nested.byId("outer").scrollTop(), "then the outer one to where the target is now");
    }

    @Test
    void removedElementsForgetPendingScrolls() {
        s.scrollTo(0, 100, ScrollBehavior.SMOOTH);
        s.scrollTo(0, 100);
        s.remove();
        page.frame(16);
        assertEquals(List.of(), events);
    }
}
