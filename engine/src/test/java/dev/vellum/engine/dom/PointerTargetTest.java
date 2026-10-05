package dev.vellum.engine.dom;

import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Where automation points to reach an element ({@link Document#pointerTarget}): the centre of the part of it that
 * shows ({@link Element#visibleRect}), scrolled into view when none does, and only when nothing else is painted there.
 */
class PointerTargetTest {
    /** A 100x50 scroller over 200px of content, "half" at content y 60..100 and "far" at 150..170, then "below". */
    private final Page page = new TestHost().load("""
            <div id=s style="overflow: auto; width: 100px; height: 50px">
              <div style="height: 60px"></div>
              <div id=half style="height: 40px"></div>
              <div style="height: 50px"></div>
              <div id=far style="height: 20px"></div>
              <div style="height: 30px"></div>
            </div>
            <div id=below style="height: 50px"></div>""");
    private final Element s = page.byId("s");

    @Test
    void halfScrolledOutAimsAtThePartThatShows() {
        Element half = page.byId("half");
        s.scrollTo(0, 30);
        // Painted at y 30..70; the scroller clips at 50.
        assertArrayEquals(new float[] {0, 30, 100, 40}, half.getBoundingClientRect(), 1e-3f);
        assertArrayEquals(new float[] {0, 30, 100, 20}, half.visibleRect(), 1e-3f);
        assertSame(page.byId("below"), page.doc.hitTest(50, 50).element(), "its border box's centre is below the scroller");
        assertArrayEquals(new float[] {50, 40}, page.doc.pointerTarget(half), 1e-3f);
        assertEquals(30, s.scrollTop(), "no scrolling: part of it shows");
    }

    @Test
    void fullyScrolledOutIsScrolledIntoViewFirst() {
        Element far = page.byId("far");
        assertNull(far.visibleRect());
        float[] at = page.doc.pointerTarget(far);
        assertEquals(120, s.scrollTop(), "the least scroll that shows it: its bottom edge at the scroller's");
        assertArrayEquals(new float[] {50, 40}, at, 1e-3f);
        assertSame(far, page.doc.hitTest(at[0], at[1]).element());
    }

    @Test
    void nestedScrollersAndTheViewportClipToo() {
        Page nested = new TestHost().load("""
                <div id=outer style="overflow: hidden; height: 50px">
                  <div style="height: 40px"></div>
                  <div id=inner style="overflow: auto; height: 40px">
                    <div id=t style="height: 30px"></div><div style="height: 100px"></div>
                  </div>
                </div>
                <div id=wide style="position: absolute; left: 300px; top: 100px; width: 40px; height: 10px"></div>""");
        // t is at y 40..70 in inner (40..80), which outer cuts at 50.
        assertArrayEquals(new float[] {0, 40, 320, 10}, nested.byId("t").visibleRect(), 1e-3f);
        assertArrayEquals(new float[] {300, 100, 20, 10}, nested.byId("wide").visibleRect(), 1e-3f,
                "the viewport is 320 wide");
        assertArrayEquals(new float[] {310, 105}, nested.doc.pointerTarget(nested.byId("wide")), 1e-3f);
    }

    @Test
    void coveredByAPositionedSiblingIsNotReached() {
        Page covered = new TestHost().load("""
                <div style="position: relative; width: 100px; height: 100px">
                  <button id=under style="display: block; width: 100px; height: 40px">Under</button>
                  <div id=cover style="position: absolute; left: 0; top: 0; width: 100px; height: 30px"></div>
                </div>""");
        Element under = covered.byId("under"), cover = covered.byId("cover");
        assertArrayEquals(new float[] {0, 0, 100, 40}, under.visibleRect(), 1e-3f, "covered still shows");
        assertSame(cover, covered.doc.hitTest(50, 20).element());
        assertNull(covered.doc.pointerTarget(under));
        cover.setAttribute("style", cover.getAttribute("style") + "; pointer-events: none");
        assertArrayEquals(new float[] {50, 20}, covered.doc.pointerTarget(under), 1e-3f, "clicks go through it now");
        assertNull(covered.doc.pointerTarget(cover), "and it takes none itself");
    }

    @Test
    void revealingWholeScrollsAHalfShownElementAllTheWayIn() {
        Element half = page.byId("half");
        s.scrollTo(0, 30);
        assertArrayEquals(new float[] {0, 30, 100, 20}, page.doc.reveal(half, false), 1e-3f);
        assertEquals(30, s.scrollTop(), "part of it shows: left as it is");
        assertArrayEquals(new float[] {0, 10, 100, 40}, page.doc.reveal(half, true), 1e-3f);
        assertEquals(50, s.scrollTop(), "the least scroll that shows all of it");
    }

    @Test
    void geometryIsReadAfterLayingOut() {
        Element below = page.byId("below");
        below.setAttribute("style", "height: 20px; margin-top: 5px");
        assertArrayEquals(new float[] {0, 55, 320, 20}, below.getBoundingClientRect(), 1e-3f, "no frame ran");
        page.byId("s").setAttribute("style", "display: none");
        assertArrayEquals(new float[] {0, 5, 320, 20}, below.visibleRect(), 1e-3f);
    }

    @Test
    void nothingToReachWithoutABox() {
        Element gone = page.byId("half");
        gone.setAttribute("style", "display: none");
        assertNull(page.doc.pointerTarget(gone));
        assertNull(gone.visibleRect());
        Element detached = page.doc.createElement("div");
        assertNull(page.doc.pointerTarget(detached));
    }
}
