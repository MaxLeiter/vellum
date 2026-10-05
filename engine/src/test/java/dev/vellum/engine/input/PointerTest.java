package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.MouseEvent;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.style.Cursor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.vellum.engine.input.Fixture.NONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointerTest {
    /** p (0,0 100x100) holds a (0,0 50x50) and b (50,0 50x50); b holds c (50,0 20x20). */
    private final Fixture fx = new Fixture("<div id=p><div id=a></div><div id=b><span id=c></span></div></div>");
    private final Box p = fx.box("p", null, 0, 0, 100, 100);
    private final Box b = fx.box("b", p, 50, 0, 50, 50);

    {
        fx.box("a", p, 0, 0, 50, 50);
        fx.box("c", b, 0, 0, 20, 20);
    }

    private void listenBoundaries() {
        fx.listen(fx.doc.documentElement(), "mouseover", "mouseout");
        for (String id : List.of("p", "a", "b", "c")) fx.listen(fx.el(id), "mouseenter", "mouseleave");
    }

    @Test
    void hoverBoundaryEventsInOrder() {
        listenBoundaries();
        assertTrue(fx.input.mouseMove(10, 10, NONE));
        assertEquals(List.of("mouseover:a", "mouseenter:p", "mouseenter:a"), fx.log);
        fx.log.clear();
        fx.move(60, 30);
        assertEquals(List.of("mouseout:a", "mouseleave:a", "mouseover:b", "mouseenter:b"), fx.log);
        fx.log.clear();
        fx.move(55, 5);
        assertEquals(List.of("mouseout:b", "mouseover:c", "mouseenter:c"), fx.log, "b stays entered: c is inside it");
        fx.log.clear();
        fx.move(56, 6);
        assertEquals(List.of(), fx.log);
        assertFalse(fx.input.mouseMove(300, 300, NONE));
        assertEquals(List.of("mouseout:c", "mouseleave:c", "mouseleave:b", "mouseleave:p"), fx.log);
    }

    @Test
    void hoverFlagsCoverTheTargetAndItsAncestors() {
        fx.move(55, 5);
        assertTrue(fx.el("c").isHovered() && fx.el("b").isHovered() && fx.el("p").isHovered());
        assertFalse(fx.el("a").isHovered());
        fx.move(10, 10);
        assertFalse(fx.el("c").isHovered() || fx.el("b").isHovered());
        assertTrue(fx.el("a").isHovered());
    }

    @Test
    void clickGoesToTheCommonAncestorOfPressAndRelease() {
        fx.listen(fx.doc.documentElement(), "click");
        fx.click(10, 10);
        fx.time(1000);
        fx.down(10, 10);
        fx.up(60, 30);
        fx.time(2000);
        fx.down(55, 5);
        fx.up(60, 30);
        assertEquals(List.of("click:a", "click:p", "click:b"), fx.log);
    }

    @Test
    void doubleClickNeedsTwoQuickNearbyClicks() {
        List<String> seen = new ArrayList<>();
        fx.doc.documentElement().addEventListener("click", e -> seen.add("click" + ((MouseEvent) e).detail));
        fx.doc.documentElement().addEventListener("mousedown", e -> seen.add("down" + ((MouseEvent) e).detail));
        fx.doc.documentElement().addEventListener("dblclick", e -> seen.add("dbl" + ((MouseEvent) e).detail));
        fx.click(10, 10);
        fx.time(300);
        fx.click(13, 12);
        fx.time(500);
        fx.click(13, 12);
        assertEquals(List.of("down1", "click1", "down2", "click2", "dbl2", "down3", "click3"), seen);
        seen.clear();
        fx.time(1000);
        fx.click(13, 12);
        fx.time(1100);
        fx.click(20, 12);
        assertEquals(List.of("down1", "click1", "down1", "click1"), seen, "too late, then too far");
    }

    @Test
    void rightButtonFiresContextmenuButNoClick() {
        fx.listen(fx.doc.documentElement(), "mousedown", "contextmenu", "mouseup", "click");
        fx.input.mouseDown(10, 10, 2, NONE);
        fx.input.mouseUp(10, 10, 2, NONE);
        assertEquals(List.of("mousedown:a", "contextmenu:a", "mouseup:a"), fx.log);
        assertFalse(fx.el("a").isActive(), ":active is for the primary button");
    }

    @Test
    void activeWhilePressed() {
        fx.down(55, 5);
        assertTrue(fx.el("c").isActive() && fx.el("b").isActive() && fx.el("p").isActive());
        assertFalse(fx.el("a").isActive());
        fx.up(10, 10);
        assertFalse(fx.el("c").isActive() || fx.el("b").isActive() || fx.el("p").isActive());
    }

    @Test
    void offsetsAreRelativeToThePaddingBox() {
        Box c = fx.el("c").box;
        c.borderLeft = 1;
        c.borderTop = 2;
        float[] offset = new float[2];
        fx.el("c").addEventListener("mousedown", e -> {
            MouseEvent m = (MouseEvent) e;
            offset[0] = m.offsetX;
            offset[1] = m.offsetY;
        });
        fx.down(60, 5);
        assertEquals(9, offset[0]);
        assertEquals(3, offset[1]);
    }

    @Test
    void cursorFollowsTheHoveredElement() {
        Fixture fx = new Fixture("<div id=d></div><input id=t><button id=btn></button><input id=off disabled>");
        fx.box("d", null, 0, 0, 10, 10);
        fx.box("t", null, 10, 0, 10, 10);
        fx.box("btn", null, 20, 0, 10, 10).style.cursor = Cursor.POINTER;
        fx.box("off", null, 30, 0, 10, 10);
        for (float x : new float[] {5, 15, 25, 6, 35, 100}) fx.move(x, 5);
        assertEquals(List.of(Cursor.DEFAULT, Cursor.TEXT, Cursor.POINTER, Cursor.DEFAULT), fx.host.cursors,
                "only changes are sent; disabled fields and empty space use the arrow");
    }

    @Test
    void pressFocusesTheNearestFocusableAncestorOrBlurs() {
        Fixture fx = new Fixture("<div id=wrap tabindex=0><span id=inner>x</span></div><div id=plain></div><input id=t>");
        fx.box("wrap", null, 0, 0, 10, 10);
        fx.box("inner", fx.el("wrap").box, 0, 0, 5, 5);
        fx.box("plain", null, 10, 0, 10, 10);
        fx.box("t", null, 20, 0, 10, 10);
        fx.click(2, 2);
        assertSame(fx.el("wrap"), fx.doc.focusedElement());
        fx.click(15, 5);
        assertNull(fx.doc.focusedElement());
        fx.el("t").addEventListener("mousedown", Event::preventDefault);
        fx.click(25, 5);
        assertNull(fx.doc.focusedElement(), "a cancelled mousedown does not focus");
    }

    @Test
    void rangeThumbDragsWithCapture() {
        Fixture fx = new Fixture("<input type=range id=r>");
        Element r = fx.el("r");
        fx.box(r, null, 0, 0, 108, 20); // track: thumb centre from x 4 to 104
        fx.listen(r, "input", "change", "mousemove");
        fx.down(54, 10);
        assertEquals("50", new RangeControl(r).text());
        assertTrue(r.isActive());
        fx.move(79, 10);
        assertEquals("75", r.value());
        fx.move(500, 300);
        assertEquals("100", r.value(), "captured: moves outside the box still drag");
        fx.up(500, 300);
        assertEquals(List.of("input:r", "mousemove:r", "input:r", "mousemove:r", "change:r"), fx.log);
        assertFalse(r.isActive());
        fx.log.clear();
        fx.move(510, 300);
        assertEquals(List.of(), fx.log, "released: the pointer is outside again");
    }

    @Test
    void removedNodesLeaveTheHoverChain() {
        fx.move(55, 5);
        Element b = fx.el("b"), c = fx.el("c");
        fx.el("p").removeChild(b);
        assertFalse(b.isHovered() || c.isHovered());
        assertTrue(fx.el("p").isHovered());
    }

    @Test
    void hoverIsRecomputedAfterLayout() {
        listenBoundaries();
        fx.move(10, 10);
        fx.log.clear();
        fx.el("a").box = null; // a moved away
        fx.input.afterLayout();
        assertEquals(List.of("mouseout:a", "mouseleave:a", "mouseover:p"), fx.log);
    }
}
