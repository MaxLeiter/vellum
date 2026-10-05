package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.paint.Coordinates;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hand-verified block flow: widths, auto margins, percentages, min/max, box-sizing and margin collapsing. */
class BlockLayoutTest {
    static void assertRect(Element e, float x, float y, float w, float h) {
        assertRect(e.box, x, y, w, h);
    }

    /** The box's border box in viewport coordinates (as painted, no transforms here) and size. */
    static void assertRect(Box b, float x, float y, float w, float h) {
        String msg = "box " + b;
        float[] r = Coordinates.boundingRect(b);
        assertEquals(x, r[0], 0.01, msg + " x");
        assertEquals(y, r[1], 0.01, msg + " y");
        assertEquals(w, b.width, 0.01, msg + " width");
        assertEquals(h, b.height, 0.01, msg + " height");
    }

    @Test
    void blocksStackAndFillTheContainingBlock() {
        TestDoc t = new TestDoc();
        Element a = t.div(t.body, "height: 10px");
        Element b = t.div(t.body, "height: 20px; padding: 2px; border: 1px");
        t.layout(320, 240);
        assertRect(a, 0, 0, 320, 10);
        assertRect(b, 0, 10, 320, 20);
        assertEquals(3, b.box.contentX());
        assertRect(t.body, 0, 0, 320, 30);
        assertSame(t.html.box, t.doc.layoutEngine().root());
        assertNull(t.doc.head().box);
    }

    @Test
    void contentBoxSizingAddsPaddingAndBorder() {
        TestDoc t = new TestDoc();
        Element a = t.div(t.body, "box-sizing: content-box; width: 100px; height: 10px; padding: 10px; border: 2px");
        t.layout();
        assertRect(a, 0, 0, 124, 34);
    }

    @Test
    void autoMarginsCentre() {
        TestDoc t = new TestDoc();
        Element a = t.div(t.body, "width: 100px; height: 10px; margin: 0 auto");
        Element b = t.div(t.body, "width: 100px; height: 10px; margin-left: auto");
        t.layout(320, 240);
        assertRect(a, 110, 0, 100, 10);
        assertRect(b, 220, 10, 100, 10);
    }

    @Test
    void percentagesAndMinMax() {
        TestDoc t = new TestDoc();
        Element outer = t.div(t.body, "width: 200px; height: 100px");
        Element a = t.div(outer, "width: 50%; height: 25%");
        Element b = t.div(outer, "width: 10%; min-width: 50px; max-height: 5px; height: 50px");
        Element c = t.div(outer, "max-width: 30%; height: 10px; padding: 0 5%");
        t.layout();
        assertRect(a, 0, 0, 100, 25);
        assertRect(b, 0, 25, 50, 5);
        assertRect(c, 0, 30, 60, 10);
        assertEquals(10, c.box.paddingLeft, 0.01);
    }

    @Test
    void percentageHeightOfAutoHeightParentIsAuto() {
        TestDoc t = new TestDoc();
        Element outer = t.div(t.body, "");
        Element a = t.div(outer, "height: 50%");
        t.div(a, "height: 7px");
        t.layout();
        assertRect(a, 0, 0, 320, 7);
    }

    @Test
    void rootPercentHeightsResolveAgainstTheViewport() {
        TestDoc t = new TestDoc();
        t.html.style.height = TestStyles.length("100%", t.html.style);
        t.body.style.height = TestStyles.length("50%", t.body.style);
        t.layout(320, 240);
        assertRect(t.body, 0, 0, 320, 120);
    }

    @Test
    void aspectRatioGivesHeightFromWidth() {
        TestDoc t = new TestDoc();
        Element a = t.div(t.body, "width: 100px; aspect-ratio: 2");
        t.layout();
        assertRect(a, 0, 0, 100, 50);
    }

    @Test
    void siblingMarginsCollapse() {
        TestDoc t = new TestDoc();
        Element a = t.div(t.body, "height: 10px; margin-bottom: 10px");
        Element b = t.div(t.body, "height: 10px; margin-top: 20px; margin-bottom: -5px");
        Element c = t.div(t.body, "height: 10px; margin-top: 3px");
        t.layout();
        assertRect(a, 0, 0, 320, 10);
        assertRect(b, 0, 30, 320, 10);
        // 3 and -5 collapse to -2.
        assertRect(c, 0, 38, 320, 10);
    }

    @Test
    void parentAndFirstChildMarginsCollapseThroughBody() {
        TestDoc t = new TestDoc();
        Element outer = t.div(t.body, "margin-top: 10px");
        Element inner = t.div(outer, "margin-top: 20px; height: 5px");
        t.layout();
        // The 20px margin escapes inner, outer and body; the root contains it.
        assertRect(t.body, 0, 20, 320, 5);
        assertRect(outer, 0, 20, 320, 5);
        assertRect(inner, 0, 20, 320, 5);
        assertRect(t.html, 0, 0, 320, 25);
    }

    @Test
    void paddingOrBorderSeparatesParentAndChildMargins() {
        TestDoc t = new TestDoc();
        Element outer = t.div(t.body, "padding-top: 1px; margin-top: 10px");
        Element inner = t.div(outer, "margin-top: 20px; margin-bottom: 7px; height: 5px");
        Element after = t.div(t.body, "height: 1px");
        t.layout();
        assertRect(outer, 0, 10, 320, 26);
        assertRect(inner, 0, 31, 320, 5);
        // The inner bottom margin escapes outer (no bottom padding/border) and separates the next sibling.
        assertRect(after, 0, 43, 320, 1);
    }

    @Test
    void emptyBlocksCollapseThrough() {
        TestDoc t = new TestDoc();
        t.div(t.body, "height: 10px; margin-bottom: 10px");
        t.div(t.body, "margin-top: 5px; margin-bottom: 15px");
        Element c = t.div(t.body, "height: 10px; margin-top: 8px");
        t.layout();
        assertRect(c, 0, 25, 320, 10);
    }

    @Test
    void independentFormattingContextsContainChildMargins() {
        TestDoc t = new TestDoc();
        Element scroller = t.div(t.body, "overflow: hidden");
        Element inner = t.div(scroller, "margin-top: 20px; margin-bottom: 4px; height: 5px");
        t.layout();
        assertRect(scroller, 0, 0, 320, 29);
        assertRect(inner, 0, 20, 320, 5);
    }

    @Test
    void displayNoneAndContents() {
        TestDoc t = new TestDoc();
        Element hidden = t.div(t.body, "display: none");
        Element hiddenChild = t.div(hidden, "height: 10px");
        Element contents = t.div(t.body, "display: contents");
        Element child = t.div(contents, "height: 10px");
        hiddenChild.box = new Box(Box.Kind.BLOCK, hiddenChild, hiddenChild.style);
        t.layout();
        assertNull(hidden.box);
        assertNull(hiddenChild.box, "stale boxes are cleared");
        assertNull(contents.box);
        assertRect(child, 0, 0, 320, 10);
        assertTrue(t.body.box.children.contains(child.box));
    }
}
