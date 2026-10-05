package dev.vellum.engine.paint;

import dev.vellum.engine.layout.Box;
import dev.vellum.engine.layout.Fragment;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.style.Overflow;
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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaintOrderTest {
    private final TestTree t = new TestTree();

    private static final int ROOT = 0xFF000001, A = 0xFF00000A, B = 0xFF00000B, NEG = 0xFF0000FF, POS_AUTO = 0xFF000100,
            Z1 = 0xFF000201, Z2 = 0xFF000202, C = 0xFF00000C;

    @Test
    void followsAppendixE() {
        Box root = t.div(0, 0, 100, 100, ROOT);
        Box a = add(root, t.div(0, 0, 100, 20, A));
        line(a, t.text(a.element, "a", 0, 0));
        add(root, t.div(0, 10, 100, 20, B)); // overlaps a's text, but its background goes under it
        add(root, z(t.div(0, 0, 10, 10, Z2), Position.ABSOLUTE, 2));
        add(root, position(t.div(0, 0, 10, 10, POS_AUTO), Position.RELATIVE));
        add(root, z(t.div(0, 0, 10, 10, Z1), Position.RELATIVE, 1));
        add(root, z(t.div(0, 0, 10, 10, NEG), Position.RELATIVE, -1));
        RecordingCanvas c = t.paint(root);
        assertEquals(List.of("fillRect:ff000001", "fillRect:ff0000ff", "fillRect:ff00000a", "fillRect:ff00000b",
                "drawText:a", "fillRect:ff000100", "fillRect:ff000201", "fillRect:ff000202"), c.trace());
    }

    @Test
    void stackingContextsPaintAsAUnit() {
        Box root = t.div(0, 0, 100, 100, ROOT);
        Box s1 = add(root, z(t.div(0, 0, 50, 50, Z1), Position.RELATIVE, 1));
        add(s1, z(t.div(0, 0, 10, 10, C), Position.RELATIVE, 100));
        add(root, z(t.div(0, 0, 50, 50, Z2), Position.RELATIVE, 2));
        assertEquals(List.of(ROOT, Z1, C, Z2), t.paint(root).fills());
    }

    @Test
    void positionedDescendantsOfNonContextsJoinTheEnclosingContext() {
        Box root = t.div(0, 0, 100, 100, ROOT);
        Box p = add(root, position(t.div(0, 0, 50, 50, POS_AUTO), Position.RELATIVE));
        add(p, z(t.div(0, 0, 10, 10, NEG), Position.RELATIVE, -1));
        add(root, t.div(0, 0, 10, 10, A));
        // NEG is in root's context: under the in-flow A even though its parent paints later.
        assertEquals(List.of(ROOT, NEG, A, POS_AUTO), t.paint(root).fills());
    }

    @Test
    void zIndexAppliesToFlexItemsWithoutPosition() {
        Box root = t.div(0, 0, 100, 100, ROOT);
        Box item1 = add(root, t.div(0, 0, 10, 10, Z2));
        style(item1).isFlexOrGridItemHint = true;
        style(item1).zIndexAuto = false;
        style(item1).zIndex = 2;
        add(root, t.div(0, 0, 10, 10, A));
        assertEquals(List.of(ROOT, A, Z2), t.paint(root).fills());
    }

    @Test
    void inlineFragmentsPaintInLineOrderAndAtomicsOnce() {
        Box root = t.div(0, 0, 100, 100, ROOT);
        var span = t.element("span");
        span.style.display = Display.INLINE;
        span.style.backgroundColor = C;
        Box inlineBlock = t.div(30, 0, 10, 9, B);
        inlineBlock.atomicInline = true;
        add(root, inlineBlock);
        line(root, TestTree.inline(span, 0, 0, 20, 9, 2), t.text(span, "x", 2, 0),
                new Fragment.Atomic(inlineBlock), t.text(root.element, "y", 45, 0));
        assertEquals(List.of("fillRect:ff000001", "fillRect:ff00000c", "drawText:x", "fillRect:ff00000b", "drawText:y"),
                t.paint(root).trace());
    }

    @Test
    void opacityMultipliesAlphaAndZeroSkips() {
        Box root = t.div(0, 0, 100, 100, ROOT);
        Box half = add(root, t.div(0, 0, 10, 10, A));
        style(half).opacity = 0.5f;
        add(half, t.div(0, 0, 5, 5, C));
        Box gone = add(root, t.div(0, 0, 10, 10, B));
        style(gone).opacity = 0;
        RecordingCanvas c = t.paint(root);
        assertEquals(List.of(ROOT, A, C), c.fills());
        assertEquals(1, c.calls.get(0).alpha());
        assertEquals(0.5f, c.calls.get(1).alpha());
        assertEquals(0.5f, c.calls.get(2).alpha());
    }

    @Test
    void hiddenBoxesStillPaintVisibleChildren() {
        Box root = t.div(0, 0, 100, 100, ROOT);
        Box hidden = add(root, t.div(0, 0, 10, 10, A));
        style(hidden).visibility = Visibility.HIDDEN;
        add(hidden, t.div(0, 0, 5, 5, C));
        assertEquals(List.of(ROOT, C), t.paint(root).fills());
    }

    @Test
    void transformsRotateAroundTheOrigin() {
        Box root = t.div(0, 0, 100, 100, ROOT);
        Box box = add(root, t.div(10, 10, 20, 10, A));
        style(box).transform = List.of(new TransformFunction.Rotate(90));
        RecordingCanvas.Call call = t.paint(root).ops("fillRect").get(1);
        // centre (20, 15); a 20×10 box turned a quarter is 10×20 around it
        assertArrayEquals(new float[] {15, 5, 10, 20}, call.bounds(), 1e-4f);
    }

    @Test
    void transformPercentagesAndOriginUseTheBorderBox() {
        Box root = t.div(0, 0, 100, 100, ROOT);
        Box box = add(root, t.div(10, 0, 20, 10, A));
        style(box).transform = List.of(new TransformFunction.Translate(Length.percent(50), Length.ZERO),
                new TransformFunction.Scale(2, 2));
        style(box).transformOriginX = Length.ZERO;
        style(box).transformOriginY = Length.ZERO;
        RecordingCanvas.Call call = t.paint(root).ops("fillRect").get(1);
        assertArrayEquals(new float[] {20, 0, 40, 20}, call.bounds(), 1e-4f);
    }

    @Test
    void overflowClipsAndScrollsChildren() {
        Box root = t.div(0, 0, 100, 100, ROOT);
        Box list = add(root, scroller(t.div(10, 10, 50, 50, A), Overflow.AUTO, 0, 20, 50, 200));
        list.borderTop = list.borderLeft = list.borderRight = list.borderBottom = 1;
        add(list, t.div(1, 31, 48, 10, C));
        RecordingCanvas.Call child = t.paint(root).ops("fillRect").get(2);
        assertEquals(C, child.color());
        assertEquals(21, child.y(), 1e-4, "10 + 31 - 20");
        assertArrayEquals(new float[] {11, 11, 48, 48}, child.clip(), 1e-4f);
    }

    @Test
    void absoluteBoxesEscapeScrollersBelowTheirContainingBlock() {
        Box root = t.div(0, 0, 100, 100, ROOT);
        Box list = add(root, scroller(t.div(0, 0, 50, 50, A), Overflow.HIDDEN, 0, 20, 50, 200));
        Box abs = add(list, position(t.div(0, 60, 10, 10, C), Position.ABSOLUTE));
        RecordingCanvas.Call escaped = t.paint(root).ops("fillRect").get(2);
        assertNull(escaped.clip());
        assertEquals(60, escaped.y(), 1e-4);

        position(list, Position.RELATIVE); // now the scroller is the containing block, as layout records
        abs.containingBlock = list;
        t.doc.invalidateStacking();
        RecordingCanvas.Call inside = t.paint(root).ops("fillRect").get(2);
        assertArrayEquals(new float[] {0, 0, 50, 50}, inside.clip(), 1e-4f);
        assertEquals(40, inside.y(), 1e-4);
    }

    @Test
    void relativeDescendantsAreClippedAndScrolledByStaticScrollers() {
        Box root = t.div(0, 0, 100, 100, ROOT);
        Box list = add(root, scroller(t.div(0, 0, 50, 50, A), Overflow.AUTO, 0, 5, 50, 200));
        add(list, position(t.div(0, 10, 10, 10, C), Position.RELATIVE));
        RecordingCanvas.Call call = t.paint(root).ops("fillRect").stream().filter(f -> f.color() == C).findFirst().orElseThrow();
        assertEquals(5, call.y(), 1e-4);
        assertArrayEquals(new float[] {0, 0, 50, 50}, call.clip(), 1e-4f);
    }

    @Test
    void textShadowsPaintUnderTheText() {
        Box root = t.div(0, 0, 100, 100, 0);
        var p = root.element;
        p.style.color = 0xFFFFFFFF;
        p.style.textShadow = List.of(new dev.vellum.engine.style.Shadow(1, 2, 0, 0, 0xFF3F3F3F, false));
        line(root, t.text(p, "hi", 4, 4));
        RecordingCanvas c = t.paint(root);
        List<RecordingCanvas.Call> texts = c.ops("drawText");
        assertEquals(2, texts.size());
        assertEquals(0xFF3F3F3F, texts.get(0).color());
        assertArrayEquals(new float[] {5, 6}, texts.get(0).args(), 1e-4f);
        assertEquals(0xFFFFFFFF, texts.get(1).color());

        p.style.textShadow = List.of(dev.vellum.engine.style.Shadow.MINECRAFT);
        texts = t.paint(root).ops("drawText");
        assertEquals(1, texts.size());
        assertTrue(texts.getFirst().shadow());
    }

    @Test
    void letterSpacingDrawsGlyphByGlyph() {
        Box root = t.div(0, 0, 100, 100, 0);
        root.element.style.letterSpacing = 1;
        root.element.style.underline = true;
        line(root, t.text(root.element, "ab", 10, 0));
        List<RecordingCanvas.Call> texts = t.paint(root).ops("drawText");
        assertEquals(List.of("a", "b"), texts.stream().map(RecordingCanvas.Call::text).toList());
        assertEquals(10, texts.get(0).x(), 1e-4);
        assertEquals(10 + 6 + 1, texts.get(1).x(), 1e-4, "advance of 'a' in the test font, plus the spacing");
        assertEquals(Canvas.UNDERLINE, texts.get(0).decorations());
    }

    @Test
    void outlineAndScrollbarsComeAfterContent() {
        Box root = t.div(0, 0, 100, 100, ROOT);
        Box list = add(root, scroller(t.div(0, 0, 50, 50, A), Overflow.AUTO, 0, 0, 50, 100));
        style(list).outlineStyle = dev.vellum.engine.style.BorderStyle.SOLID;
        style(list).outlineWidth = 1;
        style(list).outlineColor = B;
        add(list, t.div(0, 0, 50, 10, C));
        List<String> trace = t.paint(root).trace();
        assertEquals(List.of("fillRect:ff000001", "fillRect:ff00000a", "fillRect:ff00000c", "fillRect:20000000",
                "fillRect:80ffffff", "fillBorder:ff00000b"), trace);
    }

    @Test
    void hoveredScrollbarsWiden() {
        Box root = t.div(0, 0, 100, 100, ROOT);
        Box list = add(root, scroller(t.div(0, 0, 50, 50, A), Overflow.AUTO, 0, 0, 50, 100));
        assertEquals(2, t.paint(root).ops("fillRect").getLast().w(), 1e-5);
        // Scrollbar hover is tracked by the input handler from the pointer position over the bar.
        t.doc.input().setHitTester((x, y) -> t.doc.painter().hitTest(root, x, y));
        t.doc.input().mouseMove(49, 10, dev.vellum.engine.event.Modifiers.NONE);
        RecordingCanvas.Call thumb = t.paint(root).ops("fillRect").getLast();
        assertArrayEquals(new float[] {46, 0, 4, 25}, thumb.bounds(), 1e-5f);
    }

    @Test
    void replacedContentHonoursObjectFit() {
        Box root = t.div(0, 0, 100, 100, 0);
        var img = t.element("img");
        img.replaced = new Fixed(16, 16);
        Box box = add(root, t.box(Box.Kind.REPLACED, img, 10, 10, 32, 16));
        img.style.objectFit = dev.vellum.engine.style.ObjectFit.CONTAIN;
        RecordingCanvas.Call contain = t.paint(root).ops("drawReplaced").getFirst();
        assertArrayEquals(new float[] {18, 10, 16, 16}, contain.bounds(), 1e-4f);
        assertNull(contain.clip());

        img.style.objectFit = dev.vellum.engine.style.ObjectFit.COVER;
        RecordingCanvas.Call cover = t.paint(root).ops("drawReplaced").getFirst();
        assertArrayEquals(new float[] {10, 2, 32, 32}, cover.bounds(), 1e-4f);
        assertArrayEquals(new float[] {10, 10, 32, 16}, cover.clip(), 1e-4f);

        img.style.objectFit = dev.vellum.engine.style.ObjectFit.FILL;
        assertArrayEquals(new float[] {10, 10, 32, 16}, t.paint(root).ops("drawReplaced").getFirst().bounds(), 1e-4f);
        assertEquals(box, img.box);
    }

    @Test
    void edgesSnapToDevicePixels() {
        Box root = t.div(0, 0, 100, 100, 0);
        add(root, t.div(10.3f, 0.1f, 5.3f, 2.2f, A));
        RecordingCanvas c = new RecordingCanvas();
        c.devicePixel = 0.5f;
        RecordingCanvas.Call call = t.paint(root, c).ops("fillRect").getFirst();
        assertArrayEquals(new float[] {10.5f, 0, 5.0f, 2.5f}, call.args(), 1e-5f);
    }

    @Test
    void boxesWithoutChildrenOrLinesNeedNoClip() {
        Box root = t.div(0, 0, 100, 100, ROOT);
        scroller(root, Overflow.HIDDEN, 0, 0, 100, 100);
        RecordingCanvas c = t.paint(root);
        assertEquals(1, c.calls.size());
    }

    @Test
    void zIndexChangesWithoutARelayoutRepaintAndHitInTheNewOrder() {
        var doc = new dev.vellum.engine.testing.TestHost().load("""
                <div id=a style="position: absolute; width: 10px; height: 10px; background: #00000a"></div>
                <div id=b style="position: absolute; width: 10px; height: 10px; background: #00000b"></div>""");
        RecordingCanvas before = new RecordingCanvas();
        doc.paint(before);
        assertEquals(List.of(0xFF00000A, 0xFF00000B), before.fills());
        assertEquals("b", doc.hitTest(5, 5).element().id());
        int layouts = doc.layoutVersion();
        doc.getElementById("a").setAttribute("style",
                "position: absolute; width: 10px; height: 10px; background: #00000a; z-index: 1");
        doc.frame(16);
        assertEquals(layouts, doc.layoutVersion(), "z-index does not affect layout");
        RecordingCanvas after = new RecordingCanvas();
        doc.paint(after);
        assertEquals(List.of(0xFF00000B, 0xFF00000A), after.fills());
        assertEquals("a", doc.hitTest(5, 5).element().id());
    }

    private static void line(Box block, Fragment... fragments) {
        TestTree.line(block, 0, 0, block.width, 9, fragments);
    }

    record Fixed(float intrinsicWidth, float intrinsicHeight) implements dev.vellum.engine.host.ReplacedContent {}
}
