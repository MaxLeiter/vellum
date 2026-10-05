package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.RecordingCanvas;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaintOrderTest {
    private static final int ROOT = 0xFF000001, A = 0xFF00000A, B = 0xFF00000B, NEG = 0xFF0000FF, POS_AUTO = 0xFF000100,
            Z1 = 0xFF000201, Z2 = 0xFF000202, C = 0xFF00000C;

    /** A page whose body holds a 100x100 stacking context (background ROOT) with {@code content}. */
    private static Page root(String content) {
        return new TestHost().load("<div id=root style='position: relative; z-index: 0; width: 100px; height: 100px; "
                + "background: #000001'>" + content + "</div>");
    }

    private static RecordingCanvas.Call fill(RecordingCanvas c, int color) {
        return c.ops("fillRect").stream().filter(f -> f.color() == color).findFirst().orElseThrow();
    }

    @Test
    void followsAppendixE() {
        Page page = root("""
                <div style="height: 20px; background: #00000a">a</div>
                <div style="height: 20px; margin-top: -10px; background: #00000b"></div>
                <div style="position: absolute; top: 0; z-index: 2; width: 10px; height: 10px; background: #000202"></div>
                <div style="position: relative; width: 10px; height: 10px; background: #000100"></div>
                <div style="position: relative; z-index: 1; width: 10px; height: 10px; background: #000201"></div>
                <div style="position: relative; z-index: -1; width: 10px; height: 10px; background: #0000ff"></div>""");
        // b overlaps a's text, but its background goes under it.
        assertEquals(List.of("rect 0,0 100x100 #ff000001", "rect 0,50 10x10 #ff0000ff", "rect 0,0 100x20 #ff00000a",
                "rect 0,10 100x20 #ff00000b", "text 'a' 0,0 #ffffffff", "rect 0,30 10x10 #ff000100",
                "rect 0,40 10x10 #ff000201", "rect 0,0 10x10 #ff000202"), page.paint().trace());
    }

    @Test
    void stackingContextsPaintAsAUnit() {
        Page page = root("""
                <div style="position: relative; z-index: 1; width: 50px; height: 50px; background: #000201">
                  <div style="position: relative; z-index: 100; width: 10px; height: 10px; background: #00000c"></div>
                </div>
                <div style="position: relative; z-index: 2; width: 50px; height: 50px; background: #000202"></div>""");
        assertEquals(List.of(ROOT, Z1, C, Z2), page.paint().fills());
    }

    @Test
    void positionedDescendantsOfNonContextsJoinTheEnclosingContext() {
        Page page = root("""
                <div style="position: relative; width: 50px; height: 50px; background: #000100">
                  <div style="position: relative; z-index: -1; width: 10px; height: 10px; background: #0000ff"></div>
                </div>
                <div style="width: 10px; height: 10px; background: #00000a"></div>""");
        // NEG is in root's context: under the in-flow A even though its parent paints later.
        assertEquals(List.of(ROOT, NEG, A, POS_AUTO), page.paint().fills());
    }

    @Test
    void zIndexAppliesToFlexItemsWithoutPosition() {
        Page page = root("""
                <div style="display: flex">
                  <div style="z-index: 2; width: 10px; height: 10px; background: #000202"></div>
                  <div style="width: 10px; height: 10px; background: #00000a"></div>
                </div>""");
        assertEquals(List.of(ROOT, A, Z2), page.paint().fills());
    }

    @Test
    void inlineFragmentsPaintInLineOrderAndAtomicsOnce() {
        Page page = root("<span style='background: #00000c'>x</span>"
                + "<span style='display: inline-block; vertical-align: top; width: 10px; height: 9px; "
                + "background: #00000b'></span>y");
        assertEquals(List.of("rect 0,0 100x100 #ff000001", "rect 0,0 6x9 #ff00000c", "text 'x' 0,0 #ffffffff",
                "rect 6,0 10x9 #ff00000b", "text 'y' 16,0 #ffffffff"), page.paint().trace());
    }

    @Test
    void opacityMultipliesAlphaAndZeroSkips() {
        Page page = root("""
                <div style="opacity: 0.5; width: 10px; height: 10px; background: #00000a">
                  <div style="width: 5px; height: 5px; background: #00000c"></div>
                </div>
                <div style="opacity: 0; width: 10px; height: 10px; background: #00000b"></div>""");
        List<RecordingCanvas.Call> fills = page.paint().ops("fillRect");
        assertEquals(List.of(ROOT, A, C), fills.stream().map(RecordingCanvas.Call::color).toList());
        assertEquals(List.of(1f, 0.5f, 0.5f), fills.stream().map(RecordingCanvas.Call::alpha).toList());
    }

    @Test
    void hiddenBoxesStillPaintVisibleChildren() {
        Page page = root("""
                <div style="visibility: hidden; width: 10px; height: 10px; background: #00000a">
                  <div style="visibility: visible; width: 5px; height: 5px; background: #00000c"></div>
                </div>""");
        assertEquals(List.of(ROOT, C), page.paint().fills());
    }

    @Test
    void transformsRotateAroundTheOrigin() {
        Page page = root("<div style='position: absolute; left: 10px; top: 10px; width: 20px; height: 10px; "
                + "background: #00000a; transform: rotate(90deg)'></div>");
        // centre (20, 15); a 20×10 box turned a quarter is 10×20 around it
        assertArrayEquals(new float[] {15, 5, 10, 20}, fill(page.paint(), A).bounds(), 1e-4f);
    }

    @Test
    void transformPercentagesAndOriginUseTheBorderBox() {
        Page page = root("<div style='position: absolute; left: 10px; width: 20px; height: 10px; background: #00000a; "
                + "transform: translate(50%, 0) scale(2); transform-origin: 0 0'></div>");
        assertArrayEquals(new float[] {20, 0, 40, 20}, fill(page.paint(), A).bounds(), 1e-4f);
    }

    @Test
    void overflowClipsAndScrollsChildren() {
        Page page = root("""
                <div id=list style="position: absolute; left: 10px; top: 10px; width: 50px; height: 50px; overflow: auto;
                                    border: 1px solid; background: #00000a">
                  <div style="height: 30px"></div><div style="height: 10px; background: #00000c"></div><div style="height: 160px"></div>
                </div>""");
        page.byId("list").scrollTo(0, 20);
        RecordingCanvas.Call child = fill(page.paint(), C);
        assertEquals(21, child.y(), 1e-4, "10 + 1 + 30 - 20");
        assertArrayEquals(new float[] {11, 11, 48, 48}, child.clip(), 1e-4f);
    }

    @Test
    void absoluteBoxesEscapeScrollersBelowTheirContainingBlock() {
        String list = "overflow: hidden; width: 50px; height: 50px; background: #00000a";
        Page page = new TestHost().load("<div id=list style='" + list + "'><div style='height: 200px'></div>"
                + "<div style='position: absolute; top: 60px; left: 0; width: 10px; height: 10px; background: #00000c'>"
                + "</div></div>");
        Element scroller = page.byId("list");
        scroller.scrollTo(0, 20);
        RecordingCanvas.Call escaped = fill(page.paint(), C);
        assertNull(escaped.clip());
        assertEquals(60, escaped.y(), 1e-4);

        scroller.setAttribute("style", list + "; position: relative"); // now the scroller is the containing block
        page.frame();
        RecordingCanvas.Call inside = fill(page.paint(), C);
        assertArrayEquals(new float[] {0, 0, 50, 50}, inside.clip(), 1e-4f);
        assertEquals(40, inside.y(), 1e-4);
    }

    @Test
    void relativeDescendantsAreClippedAndScrolledByStaticScrollers() {
        Page page = new TestHost().load("""
                <div id=list style="overflow: auto; width: 50px; height: 50px">
                  <div style="height: 10px"></div>
                  <div style="position: relative; width: 10px; height: 10px; background: #00000c"></div>
                  <div style="height: 180px"></div>
                </div>""");
        page.byId("list").scrollTo(0, 5);
        RecordingCanvas.Call call = fill(page.paint(), C);
        assertEquals(5, call.y(), 1e-4);
        assertArrayEquals(new float[] {0, 0, 50, 50}, call.clip(), 1e-4f);
    }

    @Test
    void textShadowsPaintUnderTheText() {
        Page page = new TestHost().load(
                "<div id=p style='padding: 4px; color: #fff; text-shadow: 1px 2px #3f3f3f'>hi</div>");
        List<RecordingCanvas.Call> texts = page.paint().ops("drawText");
        assertEquals(2, texts.size());
        assertEquals(0xFF3F3F3F, texts.get(0).color());
        assertArrayEquals(new float[] {5, 6}, texts.get(0).args(), 1e-4f);
        assertEquals(0xFFFFFFFF, texts.get(1).color());

        page.byId("p").setAttribute("style", "padding: 4px; color: #fff; text-shadow: minecraft");
        page.frame();
        texts = page.paint().ops("drawText");
        assertEquals(1, texts.size());
        assertTrue(texts.getFirst().shadow(), "Minecraft's own shadow is the host's");
    }

    @Test
    void letterSpacingDrawsGlyphByGlyph() {
        Page page = new TestHost().load(
                "<div style='padding-left: 10px; letter-spacing: 1px; text-decoration: underline'>ab</div>");
        List<RecordingCanvas.Call> texts = page.paint().ops("drawText");
        assertEquals(List.of("a", "b"), texts.stream().map(RecordingCanvas.Call::text).toList());
        assertEquals(10, texts.get(0).x(), 1e-4);
        assertEquals(10 + 6 + 1, texts.get(1).x(), 1e-4, "advance of 'a' in the test font, plus the spacing");
        assertEquals(Canvas.UNDERLINE, texts.get(0).decorations());
    }

    @Test
    void outlineAndScrollbarsComeAfterContent() {
        Page page = root("""
                <div style="overflow: auto; width: 50px; height: 50px; background: #00000a; outline: 1px solid #00000b">
                  <div style="height: 10px; background: #00000c"></div><div style="height: 90px"></div>
                </div>""");
        assertEquals(List.of("rect 0,0 100x100 #ff000001", "rect 0,0 50x50 #ff00000a", "clip 0,0 50x50",
                "rect 0,0 50x10 #ff00000c", "clip 0,0 50x50", "rect 48,0 2x50 #20000000", "rect 48,0 2x25 #80ffffff",
                "border -1,-1 52x52 #ff00000b"), page.paint().trace());
    }

    @Test
    void hoveredScrollbarsWiden() {
        Page page = new TestHost().load(
                "<div style='overflow: auto; width: 50px; height: 50px'><div style='height: 100px'></div></div>");
        assertEquals(2, page.paint().ops("fillRect").getLast().w(), 1e-5);
        // Scrollbar hover is tracked by the input handler from the pointer position over the bar.
        page.move(49, 10);
        assertArrayEquals(new float[] {46, 0, 4, 25}, page.paint().ops("fillRect").getLast().bounds(), 1e-5f);
    }

    @Test
    void replacedContentHonoursObjectFit() {
        TestHost host = new TestHost();
        host.imageSizes.put("test:x.png", new float[] {16, 16});
        String img = "position: absolute; left: 10px; top: 10px; width: 32px; height: 16px; object-fit: ";
        Page page = host.load("<img id=i src=x.png style='" + img + "contain'>");
        RecordingCanvas.Call contain = page.paint().ops("drawImage").getFirst();
        assertEquals("test:x.png", contain.text());
        assertArrayEquals(new float[] {18, 10, 16, 16}, contain.bounds(), 1e-4f);
        assertNull(contain.clip());

        page.byId("i").setAttribute("style", img + "cover");
        page.frame();
        RecordingCanvas.Call cover = page.paint().ops("drawImage").getFirst();
        assertArrayEquals(new float[] {10, 2, 32, 32}, cover.bounds(), 1e-4f);
        assertArrayEquals(new float[] {10, 10, 32, 16}, cover.clip(), 1e-4f);

        page.byId("i").setAttribute("style", img + "fill");
        page.frame();
        assertArrayEquals(new float[] {10, 10, 32, 16}, page.paint().ops("drawImage").getFirst().bounds(), 1e-4f);
    }

    @Test
    void edgesSnapToDevicePixels() {
        Page page = new TestHost().load("<div style='position: absolute; left: 10.3px; top: 0.1px; width: 5.3px; "
                + "height: 2.2px; background: #00000a'></div>");
        RecordingCanvas canvas = new RecordingCanvas();
        canvas.devicePixel = 0.5f;
        float[] snapped = page.paint(canvas).ops("fillRect").getFirst().args();
        assertArrayEquals(new float[] {10.5f, 0, 5.0f, 2.5f}, snapped, 1e-5f);
    }

    @Test
    void boxesWithoutChildrenOrLinesNeedNoClip() {
        Page page = new TestHost().load(
                "<div style='overflow: hidden; width: 100px; height: 100px; background: #000001'></div>");
        assertEquals(List.of("rect 0,0 100x100 #ff000001"), page.paint().trace());
    }

    @Test
    void zIndexChangesWithoutARelayoutRepaintAndHitInTheNewOrder() {
        Page page = new TestHost().load("""
                <div id=a style="position: absolute; width: 10px; height: 10px; background: #00000a"></div>
                <div id=b style="position: absolute; width: 10px; height: 10px; background: #00000b"></div>""");
        assertEquals(List.of(A, B), page.paint().fills());
        assertEquals("b", page.doc.hitTest(5, 5).element().id());
        Object tree = page.doc.layoutEngine().root();
        page.byId("a").setAttribute("style",
                "position: absolute; width: 10px; height: 10px; background: #00000a; z-index: 1");
        page.frame();
        assertSame(tree, page.doc.layoutEngine().root(), "z-index does not affect layout: no relayout");
        assertEquals(List.of(B, A), page.paint().fills());
        assertEquals("a", page.doc.hitTest(5, 5).element().id());
    }
}
