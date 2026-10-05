package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class HitTestTest {
    /** A page whose body holds a positioned 100x100 #root with {@code content}. */
    private static Page root(String content) {
        return new TestHost().load("<div id=root style='position: relative; width: 100px; height: 100px'>" + content + "</div>");
    }

    private static HitResult hit(Page page, float x, float y) {
        return page.doc.hitTest(x, y);
    }

    private static Element at(Page page, float x, float y) {
        return hit(page, x, y).element();
    }

    @Test
    void returnsTheDeepestBoxWithLocalCoordinates() {
        Page page = root("""
                <div id=outer style="position: absolute; left: 10px; top: 10px; width: 50px; height: 50px">
                  <div id=inner style="position: absolute; left: 5px; top: 5px; width: 10px; height: 10px"></div>
                </div>""");
        Element inner = page.byId("inner");
        HitResult hit = hit(page, 17, 18);
        assertSame(inner, hit.element());
        assertSame(inner.box, hit.box());
        assertEquals(2, hit.localX(), 1e-4);
        assertEquals(3, hit.localY(), 1e-4);
        assertNull(hit.text());
        assertSame(page.byId("outer"), at(page, 40, 40));
        assertSame(page.byId("root"), at(page, 90, 90));
        assertSame(page.doc.body(), at(page, 120, 10));
        assertNull(hit(page, 120, 120), "below the document");
    }

    @Test
    void theTopmostByZIndexWins() {
        Page page = root("""
                <div id=high style="position: absolute; z-index: 5; width: 50px; height: 50px"></div>
                <div style="position: absolute; z-index: 1; width: 50px; height: 50px"></div>
                <div style="width: 50px; height: 50px"></div>""");
        assertSame(page.byId("high"), at(page, 10, 10));
    }

    @Test
    void followsTransforms() {
        Page page = root("<div id=box style='position: absolute; left: 10px; top: 10px; width: 20px; height: 10px; "
                + "transform: rotate(90deg)'></div>");
        // Rotated about its centre (20, 15) it spans x 15..25, y 5..25.
        assertSame(page.byId("box"), at(page, 20, 6));
        assertSame(page.byId("root"), at(page, 12, 15));
        HitResult hit = hit(page, 24, 15);
        // (24, 15) is 4px right of the centre: before the quarter turn that was 4px above it.
        assertEquals(10, hit.localX(), 1e-3);
        assertEquals(1, hit.localY(), 1e-3);
    }

    @Test
    void collapsedTransformsAreNotHit() {
        Page page = root("<div style='position: absolute; left: 10px; top: 10px; width: 20px; height: 10px; "
                + "transform: scale(0, 1)'></div>");
        assertSame(page.byId("root"), at(page, 20, 15));
    }

    @Test
    void followsScrollingAndClipping() {
        Page page = root("""
                <div id=list style="position: absolute; width: 50px; height: 50px; overflow: auto">
                  <div style="height: 60px"></div><div id=item style="width: 40px; height: 30px"></div>
                  <div style="height: 5px"></div><div id=tail style="width: 40px; height: 30px"></div>
                  <div style="height: 75px"></div>
                </div>
                <div style="position: absolute; width: 100px; height: 100px; pointer-events: none"></div>""");
        page.byId("list").scrollTo(0, 40);
        HitResult hit = hit(page, 10, 25);
        assertSame(page.byId("item"), hit.element());
        assertEquals(5, hit.localY(), 1e-4);
        // The item extends to y 50 on screen, but the rest is clipped away.
        assertSame(page.byId("item"), at(page, 10, 49), "the overlay above lets the pointer through");
        assertSame(page.byId("root"), at(page, 10, 56), "the tail at 55..85 is clipped away by the scroller");
        page.byId("list").scrollTo(0, 80);
        assertSame(page.byId("tail"), at(page, 10, 16));
    }

    @Test
    void pointerEventsNoneAndHiddenLetChildrenThrough() {
        String ghost = "position: absolute; width: 50px; height: 50px; ";
        Page page = root("<div id=ghost style='" + ghost + "pointer-events: none'>"
                + "<div id=child style='position: absolute; left: 10px; top: 10px; width: 10px; height: 10px; "
                + "pointer-events: auto; visibility: visible'></div></div>");
        assertSame(page.byId("root"), at(page, 5, 5));
        assertSame(page.byId("child"), at(page, 15, 15));

        page.byId("ghost").setAttribute("style", ghost + "visibility: hidden");
        page.frame();
        assertSame(page.byId("root"), at(page, 5, 5));
        assertSame(page.byId("child"), at(page, 15, 15));
    }

    @Test
    void roundedCornersAreNotHit() {
        Page page = root("<div id=round style='position: absolute; left: 10px; top: 10px; width: 40px; height: 40px; "
                + "border-top-left-radius: 20px'></div>");
        assertSame(page.byId("root"), at(page, 11, 11));
        assertSame(page.byId("round"), at(page, 30, 30));
    }

    @Test
    void textHitsReportTheNodeAndNearestOffset() {
        Page page = root("<div id=line style='padding-left: 10px'><span id=s>Hello</span></div>");
        Element span = page.byId("s");
        // Test font advances: H 6, e 6, l 3, l 3, o 6. 7px into the run is nearest the boundary after 'H'.
        HitResult hit = hit(page, 17, 4);
        assertSame(span, hit.element());
        assertSame(span.firstChild(), hit.text());
        assertEquals(1, hit.textOffset());
        assertSame(span.box, hit.box(), "the text is in the span's inline box");
        assertEquals(7, hit.localX(), 1e-4);
        assertEquals(5, hit(page, 10 + 23, 4).textOffset());
        assertEquals(3, hit(page, 10 + 14, 4).textOffset());
        // Off the text and its inline box (here they coincide): the block holding the line.
        assertSame(page.byId("line"), at(page, 50, 4));
    }

    @Test
    void letterSpacingWidensTheGlyphs() {
        Page page = root("<span style='letter-spacing: 4px'>ab</span>");
        assertEquals(1, hit(page, 9, 4).textOffset(), "a is 6 + 4 wide");
    }

    @Test
    void scrollbarsBelongToTheirScroller() {
        Page page = root("<div id=list style='overflow: auto; width: 50px; height: 50px'>"
                + "<div id=content style='height: 200px'></div></div>");
        assertSame(page.byId("list"), at(page, 49, 5));
        assertSame(page.byId("content"), at(page, 47, 5), "left of the 2px bar");
    }

    @Test
    void anonymousBoxesResolveToTheirElement() {
        Page page = root("text<div style='height: 10px'></div>");
        Element root = page.byId("root");
        Box anonymous = root.box.children.getFirst();
        assertEquals(Box.Kind.ANONYMOUS, anonymous.kind);
        HitResult hit = hit(page, 90, 4);
        assertSame(root, hit.element());
        assertSame(anonymous, hit.box());
    }

    @Test
    void absoluteBoxesOutsideAClippingScrollerStayHittable() {
        Page page = new TestHost().load("<div style='overflow: hidden; width: 50px; height: 50px'>"
                + "<div id=popup style='position: absolute; top: 60px; width: 30px; height: 30px'></div></div>");
        assertSame(page.byId("popup"), at(page, 10, 70));
    }

    @Test
    void opacityZeroIsStillHittable() {
        Page page = root("<div id=faded style='opacity: 0; width: 50px; height: 50px'></div>");
        assertSame(page.byId("faded"), at(page, 5, 5));
    }
}
