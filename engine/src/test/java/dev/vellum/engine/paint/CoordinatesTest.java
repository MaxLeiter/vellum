package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.MouseEvent;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.RecordingCanvas;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Where a box is on screen is answered once ({@link Coordinates}), and scripts, painting, hit testing and pointer
 * events agree on it: through transforms, scroll offsets, and positioned boxes escaping scrollers.
 */
class CoordinatesTest {
    private static final int RED = 0xFFFF0000, BLUE = 0xFF0000FF;

    @Test
    void transformsAndScrollingMapTheSameEverywhere() {
        Page page = new TestHost().load("""
                <div id=s style="overflow: auto; height: 50px; transform: translate(200px, 20px) scale(2)">
                  <div style="height: 200px; padding-top: 30px"><div id=t style="width: 10px; height: 10px; background: #f00"></div></div>
                </div>""");
        Element s = page.byId("s"), t = page.byId("t");
        s.scrollTo(0, 25);
        // s (320x50) is scaled about its centre (160, 25) and moved by (200, 20): p maps to 2p + (40, -5). t is at
        // (0, 30 - 25) in it, scrolled.
        float[] rect = t.getBoundingClientRect();
        assertArrayEquals(new float[] {40, 5, 20, 20}, rect, 1e-3f);
        assertArrayEquals(rect, page.paint().fill(RED).bounds(), 1e-3f);

        HitResult hit = page.doc.hitTest(50, 15);
        assertSame(t, hit.element());
        assertEquals(5, hit.localX(), 1e-3);
        assertEquals(5, hit.localY(), 1e-3);

        float[] offset = new float[2];
        t.addEventListener("mousedown", e -> {
            offset[0] = ((MouseEvent) e).offsetX;
            offset[1] = ((MouseEvent) e).offsetY;
        });
        page.down(50, 15);
        assertArrayEquals(new float[] {5, 5}, offset, 1e-3f);
    }

    @Test
    void positionedBoxesEscapeTheScrollersBelowTheirContainingBlock() {
        Page page = new TestHost().load("""
                <div id=s style="overflow: auto; height: 50px">
                  <div style="height: 300px"></div>
                  <div id=a style="position: absolute; top: 400px; left: 5px; width: 5px; height: 5px; background: #f00"></div>
                  <div id=f style="position: fixed; top: 0; right: 0; width: 5px; height: 5px; background: #00f"></div>
                </div>""");
        Element s = page.byId("s"), a = page.byId("a"), f = page.byId("f");
        assertNull(a.box.containingBlock, "placed against the viewport");
        assertEquals(300, s.scrollHeight(), "neither extends the scroller they escape");
        s.scrollTo(0, 100);
        assertArrayEquals(new float[] {5, 400, 5, 5}, a.getBoundingClientRect(), 1e-3f);
        assertArrayEquals(new float[] {315, 0, 5, 5}, f.getBoundingClientRect(), 1e-3f);
        RecordingCanvas.Call fixed = page.paint().fill(BLUE);
        assertArrayEquals(new float[] {315, 0, 5, 5}, fixed.bounds(), 1e-3f);
        assertNull(fixed.clip(), "nor are they clipped by it");
        assertSame(f, page.doc.hitTest(316, 2).element());

        // Positioned, the scroller becomes the containing block: a is in its content, scrolled and extending it.
        s.setAttribute("style", "overflow: auto; height: 50px; position: relative");
        page.frame();
        assertSame(s.box, a.box.containingBlock);
        assertEquals(405, s.scrollHeight());
        assertArrayEquals(new float[] {5, 300, 5, 5}, a.getBoundingClientRect(), 1e-3f);
        assertArrayEquals(new float[] {5, 300, 5, 5}, page.paint().fill(RED).bounds(), 1e-3f);
    }

    @Test
    void anInlineContainingBlockPlacesAgainstItsFragments() {
        Page page = new TestHost().load("""
                <p style="margin: 0; padding-left: 20px">ab <span id=sp style="position: relative">cd
                <i id=i style="position: absolute; top: 3px; left: 1px; width: 4px; height: 4px"></i></span></p>""");
        Element sp = page.byId("sp"), i = page.byId("i");
        assertSame(sp.box, i.box.containingBlock);
        float[] span = sp.getBoundingClientRect();
        assertArrayEquals(new float[] {span[0] + 1, span[1] + 3, 4, 4}, i.getBoundingClientRect(), 1e-3f);
    }

    @Test
    void gainingATransformRelaysOutButChangingOneOnlyRepaints() {
        Page page = new TestHost().load("""
                <div id=t style="margin-left: 50px; width: 100px; height: 100px">
                  <div id=a style="position: absolute; left: 0; top: 0; width: 5px; height: 5px"></div>
                </div>""");
        Element t = page.byId("t"), a = page.byId("a");
        assertNull(a.box.containingBlock);
        t.setAttribute("style", "margin-left: 50px; width: 100px; height: 100px; transform: translate(1px, 0)");
        page.frame();
        assertSame(t.box, a.box.containingBlock, "a transformed box contains positioned descendants");
        assertArrayEquals(new float[] {51, 0, 5, 5}, a.getBoundingClientRect(), 1e-3f);
        Object tree = page.doc.layoutEngine().root();
        t.setAttribute("style", "margin-left: 50px; width: 100px; height: 100px; transform: translate(3px, 0)");
        page.frame();
        assertSame(tree, page.doc.layoutEngine().root(), "a new transform value only moves paint: no relayout");
        assertArrayEquals(new float[] {53, 0, 5, 5}, a.getBoundingClientRect(), 1e-3f);
    }
}
