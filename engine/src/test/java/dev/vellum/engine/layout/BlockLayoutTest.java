package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.paint.Coordinates;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        Page page = new TestHost().load("""
                <div id=a style="height: 10px"></div>
                <div id=b style="height: 20px; padding: 2px; border: 1px solid"></div>""");
        assertRect(page.byId("a"), 0, 0, 320, 10);
        assertRect(page.byId("b"), 0, 10, 320, 20);
        assertEquals(3, page.byId("b").box.contentX());
        assertRect(page.doc.body(), 0, 0, 320, 30);
        assertSame(page.doc.documentElement().box, page.doc.layoutEngine().root());
        assertNull(page.doc.head().box);
    }

    @Test
    void contentBoxSizingAddsPaddingAndBorder() {
        Page page = new TestHost().load("<div id=a style='box-sizing: content-box; width: 100px; height: 10px; "
                + "padding: 10px; border: 2px solid'></div>");
        assertRect(page.byId("a"), 0, 0, 124, 34);
    }

    @Test
    void autoMarginsCentre() {
        Page page = new TestHost().load("""
                <div id=a style="width: 100px; height: 10px; margin: 0 auto"></div>
                <div id=b style="width: 100px; height: 10px; margin-left: auto"></div>""");
        assertRect(page.byId("a"), 110, 0, 100, 10);
        assertRect(page.byId("b"), 220, 10, 100, 10);
    }

    @Test
    void percentagesAndMinMax() {
        Page page = new TestHost().load("""
                <div style="width: 200px; height: 100px">
                  <div id=a style="width: 50%; height: 25%"></div>
                  <div id=b style="width: 10%; min-width: 50px; max-height: 5px; height: 50px"></div>
                  <div id=c style="max-width: 30%; height: 10px; padding: 0 5%"></div>
                </div>""");
        assertRect(page.byId("a"), 0, 0, 100, 25);
        assertRect(page.byId("b"), 0, 25, 50, 5);
        assertRect(page.byId("c"), 0, 30, 60, 10);
        assertEquals(10, page.byId("c").box.paddingLeft, 0.01);
    }

    @Test
    void percentageHeightOfAutoHeightParentIsAuto() {
        Page page = new TestHost().load("<div><div id=a style='height: 50%'><div style='height: 7px'></div></div></div>");
        assertRect(page.byId("a"), 0, 0, 320, 7);
    }

    @Test
    void rootPercentHeightsResolveAgainstTheViewport() {
        Page page = new TestHost().load("<style>html { height: 100% } body { height: 50% }</style>");
        assertRect(page.doc.body(), 0, 0, 320, 120);
    }

    @Test
    void aspectRatioGivesHeightFromWidth() {
        Page page = new TestHost().load("<div id=a style='width: 100px; aspect-ratio: 2'></div>");
        assertRect(page.byId("a"), 0, 0, 100, 50);
    }

    @Test
    void siblingMarginsCollapse() {
        Page page = new TestHost().load("""
                <div id=a style="height: 10px; margin-bottom: 10px"></div>
                <div id=b style="height: 10px; margin-top: 20px; margin-bottom: -5px"></div>
                <div id=c style="height: 10px; margin-top: 3px"></div>""");
        assertRect(page.byId("a"), 0, 0, 320, 10);
        assertRect(page.byId("b"), 0, 30, 320, 10);
        // 3 and -5 collapse to -2.
        assertRect(page.byId("c"), 0, 38, 320, 10);
    }

    @Test
    void parentAndFirstChildMarginsCollapseThroughBody() {
        Page page = new TestHost().load(
                "<div id=outer style='margin-top: 10px'><div id=inner style='margin-top: 20px; height: 5px'></div></div>");
        // The 20px margin escapes inner, outer and body; the root contains it.
        assertRect(page.doc.body(), 0, 20, 320, 5);
        assertRect(page.byId("outer"), 0, 20, 320, 5);
        assertRect(page.byId("inner"), 0, 20, 320, 5);
        assertRect(page.doc.documentElement(), 0, 0, 320, 25);
    }

    @Test
    void paddingOrBorderSeparatesParentAndChildMargins() {
        Page page = new TestHost().load("""
                <div id=outer style="padding-top: 1px; margin-top: 10px">
                  <div id=inner style="margin-top: 20px; margin-bottom: 7px; height: 5px"></div>
                </div>
                <div id=after style="height: 1px"></div>""");
        assertRect(page.byId("outer"), 0, 10, 320, 26);
        assertRect(page.byId("inner"), 0, 31, 320, 5);
        // The inner bottom margin escapes outer (no bottom padding/border) and separates the next sibling.
        assertRect(page.byId("after"), 0, 43, 320, 1);
    }

    @Test
    void emptyBlocksCollapseThrough() {
        Page page = new TestHost().load("""
                <div style="height: 10px; margin-bottom: 10px"></div>
                <div style="margin-top: 5px; margin-bottom: 15px"></div>
                <div id=c style="height: 10px; margin-top: 8px"></div>""");
        assertRect(page.byId("c"), 0, 25, 320, 10);
    }

    @Test
    void independentFormattingContextsContainChildMargins() {
        Page page = new TestHost().load("<div id=s style='overflow: hidden'>"
                + "<div id=inner style='margin-top: 20px; margin-bottom: 4px; height: 5px'></div></div>");
        assertRect(page.byId("s"), 0, 0, 320, 29);
        assertRect(page.byId("inner"), 0, 20, 320, 5);
    }

    @Test
    void displayNoneAndContents() {
        Page page = new TestHost().load("""
                <div id=hidden><div id=hiddenChild style="height: 10px"></div></div>
                <div id=contents style="display: contents"><div id=child style="height: 10px"></div></div>""");
        Element hidden = page.byId("hidden"), hiddenChild = page.byId("hiddenChild");
        assertNotNull(hiddenChild.box);
        hidden.setAttribute("style", "display: none");
        page.frame();
        assertNull(hidden.box);
        assertNull(hiddenChild.box, "stale boxes are cleared");
        assertNull(page.byId("contents").box);
        assertRect(page.byId("child"), 0, 0, 320, 10);
        assertTrue(page.doc.body().box.children.contains(page.byId("child").box));
    }
}
