package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.paint.Coordinates;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static dev.vellum.engine.layout.BlockLayoutTest.assertRect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Relative, absolute and fixed positioning, and scrollable overflow. */
class PositionedLayoutTest {
    /** A relatively positioned 100x50 container at x=10 with a 2px border: its padding box starts at (12, 2). */
    private static Page container(String content) {
        return new TestHost().load("<div id=c style='position: relative; width: 100px; height: 50px; margin-left: 10px; "
                + "border: 2px solid'>" + content + "</div>");
    }

    @Test
    void insetsAgainstThePositionedAncestorsPaddingBox() {
        Page page = container("""
                <div id=a style="position: absolute; top: 5px; left: 5px; width: 10px; height: 10px"></div>
                <div id=b style="position: absolute; right: 0; bottom: 0; width: 10px; height: 10px"></div>
                <div id=stretched style="position: absolute; left: 5px; right: 5px; top: 0; bottom: 0"></div>
                <div id=centred style="position: absolute; left: 0; right: 0; width: 20px; height: 4px; margin: 0 auto"></div>""");
        Box a = page.byId("a").box;
        assertRect(a, 17, 7, 10, 10);
        assertRect(page.byId("b"), 98, 38, 10, 10);
        assertRect(page.byId("stretched"), 17, 2, 86, 46);
        assertRect(page.byId("centred"), 50, 2, 20, 4);
        // Out-of-flow boxes stay children of their DOM parent's box, in its coordinate space.
        assertSame(page.byId("c").box, a.parent);
        assertTrue(a.outOfFlow);
        assertEquals(7, a.x, 0.01);
    }

    @Test
    void autoInsetsUseTheStaticPositionAndAutoWidthShrinks() {
        Page page = container("<div style='height: 20px'></div><div id=a style='position: absolute'>aaa bb</div>");
        assertRect(page.byId("a"), 12, 22, 34, 9);
    }

    @Test
    void withoutAPositionedAncestorTheViewportIsTheContainingBlock() {
        Page page = new TestHost().load("<div style='margin: 20px'><div id=a style='position: absolute; right: 0; "
                + "bottom: 0; width: 10px; height: 10px'></div></div>", 200, 100);
        assertRect(page.byId("a"), 190, 90, 10, 10);
    }

    @Test
    void fixedIgnoresPositionedAncestors() {
        Page page = container("<div id=f style='position: fixed; top: 0; left: 0; width: 10px; height: 10px'></div>");
        assertRect(page.byId("f"), 0, 0, 10, 10);
        assertSame(page.byId("c").box, page.byId("f").box.parent);
    }

    @Test
    void absolutePseudoElementCoversItsHost() {
        Page page = container("<style>#c::before { content: ''; position: absolute; inset: 0 }</style>");
        Box before = page.byId("c").box.children.get(0);
        assertEquals(Box.Kind.PSEUDO, before.kind);
        assertRect(before, 12, 2, 96, 46);
    }

    @Test
    void absoluteInsideARelativeInlineUsesItsFragments() {
        Page page = new TestHost().load("<div>aa<span style='position: relative'>bb"
                + "<div id=a style='position: absolute; top: 0; left: 0; width: 2px; height: 2px'></div></span></div>");
        assertRect(page.byId("a"), 12, 0, 2, 2);
    }

    @Test
    void relativeOffsetsDoNotMoveSiblings() {
        Page page = new TestHost().load("""
                <div id=a style="position: relative; top: 5px; left: -3px; height: 10px"></div>
                <div id=b style="height: 10px"></div>""");
        assertRect(page.byId("a"), -3, 5, 320, 10);
        assertRect(page.byId("b"), 0, 10, 320, 10);
    }

    @Test
    void scrollContainersRecordTheirOverflowAndClampTheirOffset() {
        Page page = new TestHost().load("""
                <div id=s style="overflow: auto; width: 100px; height: 50px; padding: 5px; border: 1px solid">
                  <div id=child style="height: 200px; width: 150px"></div>
                </div>""");
        Element scroller = page.byId("s"), child = page.byId("child");
        // Content extent from the padding box origin, plus the end padding.
        assertEquals(160, scroller.box.scrollWidth, 0.01);
        assertEquals(210, scroller.box.scrollHeight, 0.01);
        scroller.scrollTo(0, 500);
        assertEquals(162, scroller.scrollTop(), 0.01);
        // Children are positioned ignoring the scroll offset; on screen it applies.
        assertEquals(6, child.box.y, 0.01);
        assertEquals(6 - 162, Coordinates.boundingRect(child.box)[1], 0.01);
        // Less content: the relayout clamps the offset.
        child.setAttribute("style", "height: 100px; width: 150px");
        page.frame();
        assertEquals(110 - 48, scroller.scrollTop(), 0.01);
        // A box that does not overflow reports its padding box.
        assertEquals(320, page.doc.body().box.scrollWidth, 0.01);
    }
}
