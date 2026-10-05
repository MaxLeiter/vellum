package dev.vellum.engine.paint;

import dev.vellum.engine.layout.Box;
import dev.vellum.engine.layout.Fragment;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.style.Overflow;
import dev.vellum.engine.style.PointerEvents;
import dev.vellum.engine.style.Position;
import dev.vellum.engine.style.TransformFunction;
import dev.vellum.engine.style.Visibility;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.vellum.engine.paint.TestTree.add;
import static dev.vellum.engine.paint.TestTree.position;
import static dev.vellum.engine.paint.TestTree.scroller;
import static dev.vellum.engine.paint.TestTree.style;
import static dev.vellum.engine.paint.TestTree.z;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class HitTestTest {
    private final TestTree t = new TestTree();
    private final Box root = t.div(0, 0, 100, 100, 0);

    @Test
    void returnsTheDeepestBoxWithLocalCoordinates() {
        Box outer = add(root, t.div(10, 10, 50, 50, 0));
        Box inner = add(outer, t.div(5, 5, 10, 10, 0));
        HitResult hit = t.hit(root, 17, 18);
        assertSame(inner.element, hit.element());
        assertSame(inner, hit.box());
        assertEquals(2, hit.localX(), 1e-4);
        assertEquals(3, hit.localY(), 1e-4);
        assertNull(hit.text());
        assertSame(outer.element, t.hit(root, 40, 40).element());
        assertSame(root.element, t.hit(root, 90, 90).element());
        assertNull(t.hit(root, 120, 10));
    }

    @Test
    void theTopmostByZIndexWins() {
        Box high = add(root, z(t.div(0, 0, 50, 50, 0), Position.RELATIVE, 5));
        add(root, z(t.div(0, 0, 50, 50, 0), Position.RELATIVE, 1));
        add(root, t.div(0, 0, 50, 50, 0));
        assertSame(high.element, t.hit(root, 10, 10).element());
    }

    @Test
    void followsTransforms() {
        Box box = add(root, t.div(10, 10, 20, 10, 0));
        style(box).transform = List.of(new TransformFunction.Rotate(90));
        // Rotated about its centre (20, 15) it spans x 15..25, y 5..25.
        assertSame(box.element, t.hit(root, 20, 6).element());
        assertSame(root.element, t.hit(root, 12, 15).element());
        HitResult hit = t.hit(root, 24, 15);
        // (24, 15) is 4px right of the centre: before the quarter turn that was 4px above it.
        assertEquals(10, hit.localX(), 1e-3);
        assertEquals(1, hit.localY(), 1e-3);
    }

    @Test
    void collapsedTransformsAreNotHit() {
        Box box = add(root, t.div(10, 10, 20, 10, 0));
        style(box).transform = List.of(new TransformFunction.Scale(0, 1));
        assertSame(root.element, t.hit(root, 20, 15).element());
    }

    @Test
    void followsScrollingAndClipping() {
        Box list = add(root, scroller(t.div(0, 0, 50, 50, 0), Overflow.AUTO, 0, 40, 50, 200));
        Box item = add(list, t.div(0, 60, 40, 30, 0));
        HitResult hit = t.hit(root, 10, 25);
        assertSame(item.element, hit.element());
        assertEquals(5, hit.localY(), 1e-4);
        // The item extends to y 50 on screen, but the rest is clipped away.
        add(root, t.div(0, 0, 100, 100, 0)).element.style.pointerEvents = PointerEvents.NONE;
        assertSame(item.element, t.hit(root, 10, 49).element());
        Box tail = add(list, t.div(0, 95, 40, 30, 0));
        assertSame(root.element, t.hit(root, 10, 56).element(), "the tail at 55..85 is clipped away by the scroller");
        list.element.scrollTo(0, 80);
        assertSame(tail.element, t.hit(root, 10, 16).element());
    }

    @Test
    void pointerEventsNoneAndHiddenLetChildrenThrough() {
        Box ghost = add(root, t.div(0, 0, 50, 50, 0));
        style(ghost).pointerEvents = PointerEvents.NONE;
        Box child = add(ghost, t.div(10, 10, 10, 10, 0));
        child.element.style.pointerEvents = PointerEvents.AUTO;
        assertSame(root.element, t.hit(root, 5, 5).element());
        assertSame(child.element, t.hit(root, 15, 15).element());

        style(ghost).pointerEvents = PointerEvents.AUTO;
        style(ghost).visibility = Visibility.HIDDEN;
        assertSame(root.element, t.hit(root, 5, 5).element());
        assertSame(child.element, t.hit(root, 15, 15).element());
    }

    @Test
    void roundedCornersAreNotHit() {
        Box round = add(root, t.div(10, 10, 40, 40, 0));
        style(round).radiusTopLeft = Length.px(20);
        assertSame(root.element, t.hit(root, 11, 11).element());
        assertSame(round.element, t.hit(root, 30, 30).element());
    }

    @Test
    void textHitsReportTheNodeAndNearestOffset() {
        var span = t.element("span");
        Fragment.TextRun run = t.text(span, "Hello", 10, 0);
        TestTree.line(root, 0, 0, 100, 9, TestTree.inline(span, 10, 0, run.width(), 9, 2), run);
        // Test font advances: H 6, e 6, l 3, l 3, o 6. 7px into the run is nearest the boundary after 'H'.
        HitResult hit = t.hit(root, 17, 4);
        assertSame(span, hit.element());
        assertSame(run.node(), hit.text());
        assertEquals(1, hit.textOffset());
        assertSame(span.box, hit.box(), "the text is in the span's inline box");
        assertEquals(7, hit.localX(), 1e-4);
        assertEquals(5, t.hit(root, 10 + 23, 4).textOffset());
        assertEquals(3, t.hit(root, 10 + 14, 4).textOffset());
        // Off the text but on the inline box (here they coincide), or off both:
        assertSame(root.element, t.hit(root, 50, 4).element());
    }

    @Test
    void letterSpacingWidensTheGlyphs() {
        var span = t.element("span");
        span.style.letterSpacing = 4;
        Fragment.TextRun run = t.text(span, "ab", 0, 0);
        TestTree.line(root, 0, 0, 100, 9, new Fragment.TextRun(run.node(), span, span.style, "ab", null, null, 0, 0, 20, 9));
        assertEquals(1, t.hit(root, 9, 4).textOffset(), "a is 6 + 4 wide");
    }

    @Test
    void scrollbarsBelongToTheirScroller() {
        Box list = add(root, scroller(t.div(0, 0, 50, 50, 0), Overflow.AUTO, 0, 0, 50, 200));
        Box content = add(list, t.div(0, 0, 50, 200, 0));
        assertSame(list.element, t.hit(root, 49, 5).element());
        assertSame(content.element, t.hit(root, 47, 5).element(), "left of the 2px bar");
    }

    @Test
    void anonymousBoxesResolveToTheirElement() {
        Box anon = add(root, t.box(Box.Kind.ANONYMOUS, root.element, 0, 0, 100, 20));
        HitResult hit = t.hit(root, 5, 5);
        assertSame(root.element, hit.element());
        assertSame(anon, hit.box());
    }

    @Test
    void absoluteBoxesOutsideAClippingScrollerStayHittable() {
        Box list = add(root, scroller(t.div(0, 0, 50, 50, 0), Overflow.HIDDEN, 0, 0, 50, 50));
        Box popup = add(list, position(t.div(0, 60, 30, 30, 0), Position.ABSOLUTE));
        assertSame(popup.element, t.hit(root, 10, 70).element());
    }

    @Test
    void opacityZeroIsStillHittable() {
        Box faded = add(root, t.div(0, 0, 50, 50, 0));
        style(faded).opacity = 0;
        assertSame(faded.element, t.hit(root, 5, 5).element());
    }
}
