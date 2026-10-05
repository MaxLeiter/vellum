package dev.vellum.engine.dom;

import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Document#settled}: false while the page will still change by itself, true once only what never ends is
 * left (infinite animations, timers, animation-frame loops, the caret).
 */
class SettledTest {
    /** Runs the next frame and paints it, as a host does, then asks. */
    private static boolean settledAfterFrame(Page page) {
        page.frame();
        page.input.tooltip(); // hosts ask every frame
        page.paint();
        return page.doc.settled();
    }

    /** Frames until the page settles; the number of frames it took. Fails after two seconds of frames. */
    private static int framesToSettle(Page page) {
        for (int n = 1; n <= 125; n++) if (settledAfterFrame(page)) return n;
        throw new AssertionError("never settled");
    }

    @Test
    void aStaticPageSettlesOncePainted() {
        Page page = new TestHost().load("<p>Hello</p>");
        assertFalse(page.doc.settled(), "loaded but not painted yet");
        page.paint();
        assertTrue(page.doc.settled());
        page.query("p").setAttribute("class", "x");
        assertFalse(page.doc.settled(), "a restyle is pending");
        assertTrue(settledAfterFrame(page));
    }

    @Test
    void transitionsAndFiniteAnimationsRunOut() {
        Page page = new TestHost().load("""
                <style>
                  @keyframes fade { to { opacity: 0 } }
                  #t { width: 10px; height: 10px; transition: opacity 100ms linear }
                  #t.off { opacity: 0 }
                  #a { width: 10px; height: 10px; animation: fade 50ms linear 3 }
                </style>
                <div id=t></div><div id=a></div>""");
        int animation = framesToSettle(page);
        assertTrue(animation >= 9, "three 50 ms iterations: " + animation + " frames");
        page.byId("t").addClass("off");
        assertFalse(page.doc.settled());
        int transition = framesToSettle(page);
        assertTrue(transition >= 6, "100 ms: " + transition + " frames");
    }

    @Test
    void infiniteAnimationsTimersAndFrameLoopsDoNotCount() {
        Page page = new TestHost().load("""
                <style>
                  @keyframes spin { to { opacity: 0 } }
                  #s { width: 10px; height: 10px; animation: spin 1s linear infinite }
                </style>
                <div id=s></div>
                <script>
                  setInterval(() => {}, 30);
                  setTimeout(() => {}, 5000);
                  requestAnimationFrame(function loop() { requestAnimationFrame(loop); });
                </script>""");
        assertEquals(1, framesToSettle(page));
        assertTrue(page.doc.animations().isAnimating(), "still spinning");
        assertTrue(page.doc.needsFrame(1000), "and still wanting frames");
    }

    @Test
    void templateUpdatesAndNextTickCallbacksWaitForTheirFrame() {
        Page page = new TestHost().load("""
                <p id=p>{{ n }}</p>
                <script>const s = vellum.state({ n: 1 })</script>""");
        framesToSettle(page);
        page.run("s.n = 2");
        assertFalse(page.doc.settled(), "the template update is pending");
        assertTrue(settledAfterFrame(page));
        assertEquals("2", page.byId("p").textContent());
        page.run("vellum.nextTick(() => { s.n = 3 })");
        assertFalse(settledAfterFrame(page), "the callback ran after the update; what it changed shows next frame");
        assertTrue(settledAfterFrame(page));
        assertEquals("3", page.byId("p").textContent());
    }

    @Test
    void smoothScrollsRunOut() {
        Page page = new TestHost().load("""
                <div id=s style="overflow: auto; height: 50px"><div style="height: 300px"></div></div>""");
        framesToSettle(page);
        page.byId("s").scrollTo(0, 200, Element.ScrollBehavior.SMOOTH);
        assertFalse(page.doc.settled());
        assertTrue(framesToSettle(page) > 3, "eased over several frames");
        assertEquals(200, page.byId("s").scrollTop());
    }

    @Test
    void aTooltipWaitsOutItsDelayButTheCaretDoesNotCount() {
        Page page = new TestHost().load("""
                <button id=b title="Hi" style="width: 50px; height: 20px">B</button>
                <button id=quiet title="" style="width: 50px; height: 20px">Q</button>
                <input id=i style="width: 50px">""");
        framesToSettle(page);
        page.hover(page.byId("b"));
        int frames = framesToSettle(page);
        assertTrue(frames * 16 >= 500, "until the tooltip shows: " + frames + " frames");
        assertNotNull(page.input.tooltip());
        page.hover(page.byId("quiet"));
        assertTrue(framesToSettle(page) < 3, "an empty title shows nothing to wait for");
        page.click(page.byId("i"));
        assertTrue(page.doc.focusedElement() == page.byId("i") && page.input.isActive(), "the caret blinks");
        assertTrue(framesToSettle(page) < 3);
    }
}
