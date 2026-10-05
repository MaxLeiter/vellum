package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.paint.Coordinates;
import dev.vellum.engine.style.Length;
import org.junit.jupiter.api.Test;

import static dev.vellum.engine.layout.BlockLayoutTest.assertRect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Relative, absolute and fixed positioning, and scrollable overflow. */
class PositionedLayoutTest {
    /** A relatively positioned 100x50 container at x=10 with a 2px border: its padding box starts at (12, 2). */
    private static Element container(TestDoc t) {
        return t.div(t.body, "position: relative; width: 100px; height: 50px; margin-left: 10px; border: 2px");
    }

    @Test
    void insetsAgainstThePositionedAncestorsPaddingBox() {
        TestDoc t = new TestDoc();
        Element c = container(t);
        Element a = t.div(c, "position: absolute; top: 5px; left: 5px; width: 10px; height: 10px");
        Element b = t.div(c, "position: absolute; right: 0; bottom: 0; width: 10px; height: 10px");
        Element stretched = t.div(c, "position: absolute; left: 5px; right: 5px; top: 0; bottom: 0");
        Element centred = t.div(c, "position: absolute; left: 0; right: 0; width: 20px; height: 4px; margin: 0 auto");
        t.layout();
        assertRect(a, 17, 7, 10, 10);
        assertRect(b, 98, 38, 10, 10);
        assertRect(stretched, 17, 2, 86, 46);
        assertRect(centred, 50, 2, 20, 4);
        // Out-of-flow boxes stay children of their DOM parent's box, in its coordinate space.
        assertSame(c.box, a.box.parent);
        assertTrue(a.box.outOfFlow);
        assertEquals(7, a.box.x, 0.01);
    }

    @Test
    void autoInsetsUseTheStaticPositionAndAutoWidthShrinks() {
        TestDoc t = new TestDoc();
        Element c = container(t);
        t.div(c, "height: 20px");
        Element a = t.div(c, "position: absolute");
        t.text(a, "aaa bb");
        t.layout();
        assertRect(a, 12, 22, 34, 9);
    }

    @Test
    void withoutAPositionedAncestorTheViewportIsTheContainingBlock() {
        TestDoc t = new TestDoc();
        Element outer = t.div(t.body, "margin: 20px");
        Element a = t.div(outer, "position: absolute; right: 0; bottom: 0; width: 10px; height: 10px");
        t.layout(200, 100);
        assertRect(a, 190, 90, 10, 10);
    }

    @Test
    void fixedIgnoresPositionedAncestors() {
        TestDoc t = new TestDoc();
        Element c = container(t);
        Element f = t.div(c, "position: fixed; top: 0; left: 0; width: 10px; height: 10px");
        t.layout();
        assertRect(f, 0, 0, 10, 10);
        assertSame(c.box, f.box.parent);
    }

    @Test
    void absolutePseudoElementCoversItsHost() {
        TestDoc t = new TestDoc();
        Element c = container(t);
        t.before(c, "content: ''; position: absolute; inset: 0");
        t.layout();
        Box before = c.box.children.get(0);
        assertEquals(Box.Kind.PSEUDO, before.kind);
        assertRect(before, 12, 2, 96, 46);
    }

    @Test
    void absoluteInsideARelativeInlineUsesItsFragments() {
        TestDoc t = new TestDoc();
        Element p = t.div(t.body, "");
        t.text(p, "aa");
        Element span = t.add(p, "span", "position: relative");
        t.text(span, "bb");
        Element a = t.div(span, "position: absolute; top: 0; left: 0; width: 2px; height: 2px");
        t.layout();
        assertRect(a, 12, 0, 2, 2);
    }

    @Test
    void relativeOffsetsDoNotMoveSiblings() {
        TestDoc t = new TestDoc();
        Element a = t.div(t.body, "position: relative; top: 5px; left: -3px; height: 10px");
        Element b = t.div(t.body, "height: 10px");
        t.layout();
        assertRect(a, -3, 5, 320, 10);
        assertRect(b, 0, 10, 320, 10);
    }

    @Test
    void scrollContainersRecordTheirOverflowAndClampTheirOffset() {
        TestDoc t = new TestDoc();
        Element scroller = t.div(t.body, "overflow: auto; width: 100px; height: 50px; padding: 5px; border: 1px");
        Element child = t.div(scroller, "height: 200px; width: 150px");
        t.layout();
        // Content extent from the padding box origin, plus the end padding.
        assertEquals(160, scroller.box.scrollWidth, 0.01);
        assertEquals(210, scroller.box.scrollHeight, 0.01);
        scroller.scrollTo(0, 500);
        assertEquals(162, scroller.scrollTop(), 0.01);
        // Children are positioned ignoring the scroll offset; on screen it applies.
        assertEquals(6, child.box.y, 0.01);
        assertEquals(6 - 162, Coordinates.boundingRect(child.box)[1], 0.01);
        // Less content: the relayout clamps the offset.
        child.style.height = Length.px(100);
        t.layout();
        assertEquals(110 - 48, scroller.scrollTop(), 0.01);
        // A box that does not overflow reports its padding box.
        assertEquals(320, t.body.box.scrollWidth, 0.01);
    }
}
