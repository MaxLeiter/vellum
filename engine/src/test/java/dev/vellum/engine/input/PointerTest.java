package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.MouseEvent;
import dev.vellum.engine.style.Cursor;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.vellum.engine.testing.Page.NONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointerTest {
    /** p (0,0 100x100) holds a (0,0 50x50) and b (50,0 50x50); b holds c (50,0 20x20). */
    private final Page page = new TestHost().load("""
            <div id=p style="display: flex; width: 100px; height: 100px">
              <div id=a style="width: 50px; height: 50px"></div>
              <div id=b style="width: 50px; height: 50px"><span id=c style="display: block; width: 20px; height: 20px"></span></div>
            </div>""");

    private void listenBoundaries() {
        page.listen(page.doc.documentElement(), "mouseover", "mouseout");
        for (String id : List.of("p", "a", "b", "c")) page.listen(page.byId(id), "mouseenter", "mouseleave");
    }

    @Test
    void hoverBoundaryEventsInOrder() {
        listenBoundaries();
        assertTrue(page.move(10, 10));
        assertEquals(List.of("mouseover:a", "mouseenter:p", "mouseenter:a"), page.takeLog());
        page.move(60, 30);
        assertEquals(List.of("mouseout:a", "mouseleave:a", "mouseover:b", "mouseenter:b"), page.takeLog());
        page.move(55, 5);
        assertEquals(List.of("mouseout:b", "mouseover:c", "mouseenter:c"), page.takeLog(),
                "b stays entered: c is inside it");
        page.move(56, 6);
        assertEquals(List.of(), page.log);
        assertFalse(page.move(300, 300));
        assertEquals(List.of("mouseout:c", "mouseleave:c", "mouseleave:b", "mouseleave:p"), page.log);
    }

    @Test
    void hoverFlagsCoverTheTargetAndItsAncestors() {
        page.move(55, 5);
        assertTrue(page.byId("c").isHovered() && page.byId("b").isHovered() && page.byId("p").isHovered());
        assertFalse(page.byId("a").isHovered());
        page.move(10, 10);
        assertFalse(page.byId("c").isHovered() || page.byId("b").isHovered());
        assertTrue(page.byId("a").isHovered());
    }

    @Test
    void clickGoesToTheCommonAncestorOfPressAndRelease() {
        page.listen(page.doc.documentElement(), "click");
        page.click(10, 10);
        page.frame(1000);
        page.down(10, 10);
        page.up(60, 30);
        page.frame(2000);
        page.down(55, 5);
        page.up(60, 30);
        assertEquals(List.of("click:a", "click:p", "click:b"), page.log);
    }

    @Test
    void doubleClickNeedsTwoQuickNearbyClicks() {
        List<String> seen = new ArrayList<>();
        Element root = page.doc.documentElement();
        root.addEventListener("click", e -> seen.add("click" + ((MouseEvent) e).detail));
        root.addEventListener("mousedown", e -> seen.add("down" + ((MouseEvent) e).detail));
        root.addEventListener("dblclick", e -> seen.add("dbl" + ((MouseEvent) e).detail));
        page.click(10, 10);
        page.frame(300);
        page.click(13, 12);
        page.frame(500);
        page.click(13, 12);
        assertEquals(List.of("down1", "click1", "down2", "click2", "dbl2", "down3", "click3"), seen);
        seen.clear();
        page.frame(1000);
        page.click(13, 12);
        page.frame(1100);
        page.click(20, 12);
        assertEquals(List.of("down1", "click1", "down1", "click1"), seen, "too late, then too far");
    }

    @Test
    void rightButtonFiresContextmenuButNoClick() {
        page.listen(page.doc.documentElement(), "mousedown", "contextmenu", "mouseup", "click");
        page.input.mouseDown(10, 10, 2, NONE);
        page.input.mouseUp(10, 10, 2, NONE);
        assertEquals(List.of("mousedown:a", "contextmenu:a", "mouseup:a"), page.log);
        assertFalse(page.byId("a").isActive(), ":active is for the primary button");
    }

    @Test
    void activeWhilePressed() {
        page.down(55, 5);
        assertTrue(page.byId("c").isActive() && page.byId("b").isActive() && page.byId("p").isActive());
        assertFalse(page.byId("a").isActive());
        page.up(10, 10);
        assertFalse(page.byId("c").isActive() || page.byId("b").isActive() || page.byId("p").isActive());
    }

    @Test
    void offsetsAreRelativeToThePaddingBox() {
        Element c = page.byId("c");
        c.setAttribute("style",
                "display: block; width: 20px; height: 20px; border-left: 1px solid; border-top: 2px solid");
        page.frame();
        float[] offset = new float[2];
        c.addEventListener("mousedown", e -> {
            MouseEvent m = (MouseEvent) e;
            offset[0] = m.offsetX;
            offset[1] = m.offsetY;
        });
        page.down(60, 5);
        assertEquals(9, offset[0]);
        assertEquals(3, offset[1]);
    }

    @Test
    void cursorFollowsTheHoveredElement() {
        Page page = new TestHost().load("""
                <div style="display: flex; height: 10px">
                  <div id=d style="width: 10px"></div><input id=t style="width: 10px"><button id=btn style="width: 10px; padding: 0"></button>
                  <input id=off disabled style="width: 10px">
                </div>""");
        for (float x : new float[] {5, 15, 25, 6, 35, 100}) page.move(x, 5);
        assertEquals(List.of(Cursor.DEFAULT, Cursor.TEXT, Cursor.POINTER, Cursor.DEFAULT), page.host.cursors,
                "only changes are sent; disabled fields and empty space use the arrow");
    }

    @Test
    void pressFocusesTheNearestFocusableAncestorOrBlurs() {
        Page page = new TestHost().load("""
                <div style="display: flex; height: 10px">
                  <div id=wrap tabindex=0 style="width: 10px"><span id=inner style="display: block; width: 5px; height: 5px"></span></div>
                  <div id=plain style="width: 10px"></div><input id=t style="width: 10px">
                </div>""");
        page.click(2, 2);
        assertSame(page.byId("wrap"), page.doc.focusedElement());
        page.click(15, 5);
        assertNull(page.doc.focusedElement());
        page.byId("t").addEventListener("mousedown", Event::preventDefault);
        page.click(page.byId("t"));
        assertNull(page.doc.focusedElement(), "a cancelled mousedown does not focus");
    }

    @Test
    void rangeThumbDragsWithCapture() {
        Page page = new TestHost().load("<input type=range id=r style='width: 108px'>"); // thumb centre from x 4 to 104
        Element r = page.byId("r");
        page.listen(r, "input", "change", "mousemove");
        page.down(54, 10);
        assertEquals("50", RangeControl.of(r).text());
        assertTrue(r.isActive());
        page.move(79, 10);
        assertEquals("75", r.value());
        page.move(500, 300);
        assertEquals("100", r.value(), "captured: moves outside the box still drag");
        page.up(500, 300);
        assertEquals(List.of("input:r", "mousemove:r", "input:r", "mousemove:r", "change:r"), page.takeLog());
        assertFalse(r.isActive());
        page.move(510, 300);
        assertEquals(List.of(), page.log, "released: the pointer is outside again");
    }

    @Test
    void removedNodesLeaveTheHoverChain() {
        page.move(55, 5);
        Element b = page.byId("b"), c = page.byId("c");
        page.byId("p").removeChild(b);
        assertFalse(b.isHovered() || c.isHovered());
        assertTrue(page.byId("p").isHovered());
    }

    @Test
    void hoverIsRecomputedAfterLayout() {
        listenBoundaries();
        page.move(10, 10);
        page.log.clear();
        page.byId("a").setAttribute("style", "width: 50px; height: 50px; margin-top: 60px"); // a moves away
        page.frame();
        assertEquals(List.of("mouseout:a", "mouseleave:a", "mouseover:p"), page.log);
    }
}
