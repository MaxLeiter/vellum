package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.vellum.engine.layout.BlockLayoutTest.assertRect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-verified inline formatting with {@code TestFonts}: at 8px most letters advance 6px ({@code i}, {@code l} and
 * punctuation less), a space 4px; the glyph box is 9px tall with the baseline 7px down.
 */
class InlineLayoutTest {
    static List<Fragment.TextRun> runs(Box box) {
        List<Fragment.TextRun> out = new ArrayList<>();
        for (LineBox line : box.lines) {
            for (Fragment f : line.fragments) if (f instanceof Fragment.TextRun r) out.add(r);
        }
        return out;
    }

    static List<Fragment.TextRun> runs(Page page, String id) {
        return runs(page.byId(id).box);
    }

    static void assertRun(Fragment.TextRun run, String text, float x, float y, float width) {
        assertEquals(text, run.text());
        assertEquals(x, run.x(), 0.01, "x of '" + text + "'");
        assertEquals(y, run.y(), 0.01, "y of '" + text + "'");
        assertEquals(width, run.width(), 0.01, "width of '" + text + "'");
    }

    @Test
    void singleLineOfText() {
        Page page = new TestHost().load("<div id=p>Hello world</div>");
        Box p = page.byId("p").box;
        assertRect(p, 0, 0, 320, 9);
        List<Fragment.TextRun> runs = runs(p);
        assertEquals(1, runs.size());
        // H e l l o = 6+6+3+3+6, space 4, w o r l d = 6+6+6+3+6
        assertRun(runs.get(0), "Hello world", 0, 0, 55);
        assertEquals(7, p.lines.get(0).baseline, 0.01);
        assertEquals(7, p.baseline, 0.01);
        assertEquals(0, runs.get(0).sourceIndex(0));
        assertEquals(11, runs.get(0).sourceIndex(runs.get(0).text().length()));
    }

    @Test
    void wrapsAtSpacesAndStripsCollapsibleWhitespace() {
        Page page = new TestHost().load("<div id=p style='width: 39px'>  aaa   bbb\n ccc  </div>");
        Box p = page.byId("p").box;
        List<Fragment.TextRun> runs = runs(p);
        assertEquals(3, p.lines.size());
        assertRun(runs.get(0), "aaa", 0, 0, 18);
        assertRun(runs.get(1), "bbb", 0, 9, 18);
        assertRun(runs.get(2), "ccc", 0, 18, 18);
        assertRect(p, 0, 0, 39, 27);
        // Source offsets map back into the text node across collapsed white space.
        assertEquals(2, runs.get(0).sourceIndex(0));
        assertEquals(8, runs.get(1).sourceIndex(0));
        assertEquals(13, runs.get(2).sourceIndex(0));
    }

    @Test
    void twoWordsFitOnALine() {
        Page page = new TestHost().load("<div id=p style='width: 40px'>aaa bbb</div>");
        assertEquals(1, page.byId("p").box.lines.size());
        assertRun(runs(page, "p").get(0), "aaa bbb", 0, 0, 40);
    }

    @Test
    void textAlign() {
        Page page = new TestHost().load("""
                <div id=c style="width: 100px; text-align: center">aaa</div>
                <div id=r style="width: 100px; text-align: right">aaa</div>""");
        assertRun(runs(page, "c").get(0), "aaa", 41, 0, 18);
        assertRun(runs(page, "r").get(0), "aaa", 82, 0, 18);
    }

    @Test
    void lineHeightAddsHalfLeading() {
        Page page = new TestHost().load("<div id=p style='line-height: 20px'>a</div>");
        assertRect(page.byId("p"), 0, 0, 320, 20);
        assertRun(runs(page, "p").get(0), "a", 0, 5.5f, 6);
        assertEquals(12.5f, page.byId("p").box.lines.get(0).baseline, 0.01);
    }

    @Test
    void inlineBoxesWithEdges() {
        Page page = new TestHost().load(
                "<div id=p>a<span id=s style='padding: 2px 3px; margin-left: 1px; border: 1px solid'>bb</span>c</div>");
        Box p = page.byId("p").box, span = page.byId("s").box;
        List<Fragment> frags = p.lines.get(0).fragments;
        // a | margin 1, border 1, padding 3 | bb | padding 3, border 1 | c
        assertInstanceOf(Fragment.TextRun.class, frags.get(0));
        Fragment.InlineBox ib = assertInstanceOf(Fragment.InlineBox.class, frags.get(1));
        assertEquals(7, ib.x(), 0.01);
        assertEquals(20, ib.width(), 0.01);
        assertEquals(-3, ib.y(), 0.01);
        assertEquals(15, ib.height(), 0.01);
        assertTrue(ib.first() && ib.last());
        assertRun((Fragment.TextRun) frags.get(2), "bb", 11, 0, 12);
        assertRun((Fragment.TextRun) frags.get(3), "c", 27, 0, 6);
        // Vertical padding paints but does not grow the line.
        assertRect(p, 0, 0, 320, 9);
        assertEquals(Box.Kind.INLINE, span.kind);
        assertRect(span, 7, -3, 20, 15);
        assertFalse(p.children.contains(span));
    }

    @Test
    void justifyStretchesSpacesExceptOnTheLastLine() {
        Page page = new TestHost().load("<div id=p style='width: 40px; text-align: justify'>aa bb cc dd</div>");
        List<Fragment.TextRun> runs = runs(page, "p");
        // Line 1 "aa bb" is 28 wide: its one space takes the 12px left over.
        assertRun(runs.get(0), "aa", 0, 0, 12);
        assertRun(runs.get(1), "bb", 28, 0, 12);
        assertRun(runs.get(2), "cc dd", 0, 9, 28);
    }

    @Test
    void forcedBreaksAndPreservedWhiteSpace() {
        Page page = new TestHost().load("""
                <div id=p style="width: 100px; text-align: justify">aa bb<br id=br> cc</div>
                <div id=pre style="white-space: pre">a  b
                c
                </div>""");
        List<Fragment.TextRun> runs = runs(page, "p");
        // A line ending in a forced break is not justified; the space after the break is stripped.
        assertRun(runs.get(0), "aa bb", 0, 0, 28);
        assertRun(runs.get(1), "cc", 0, 9, 12);
        Box br = page.byId("br").box;
        assertEquals(Box.Kind.INLINE, br.kind);
        assertEquals(28, br.x, 0.01);
        List<Fragment.TextRun> preRuns = runs(page, "pre");
        assertRun(preRuns.get(0), "a  b", 0, 0, 20);
        assertRun(preRuns.get(1), "c", 0, 9, 6);
        // The final newline ends the last line without starting another.
        assertEquals(18, page.byId("pre").box.height, 0.01);
    }

    @Test
    void spacingIndentAndTransform() {
        Page page = new TestHost().load("""
                <div id=a style="letter-spacing: 1px">ab</div>
                <div id=b style="word-spacing: 2px; text-indent: 10px">a b</div>
                <div id=c style="text-transform: uppercase">ab</div>
                <div id=d style="text-transform: capitalize">hello (world)</div>""");
        assertRun(runs(page, "a").get(0), "ab", 0, 0, 14);
        assertRun(runs(page, "b").get(0), "a b", 10, 0, 18);
        assertEquals("AB", runs(page, "c").get(0).text());
        assertEquals("Hello (World)", runs(page, "d").get(0).text());
    }

    @Test
    void breakAllAndBreakWordSplitLongWords() {
        Page page = new TestHost().load("""
                <div id=normal style="width: 20px">aaaaaaaa</div>
                <div id=all style="width: 20px; word-break: break-all">aaaaaaaa</div>
                <div id=word style="width: 20px; overflow-wrap: break-word">a aaaaaaaa</div>""");
        assertEquals(1, page.byId("normal").box.lines.size(), "overflows instead of breaking");
        assertEquals(List.of("aaa", "aaa", "aa"), runs(page, "all").stream().map(Fragment.TextRun::text).toList());
        // break-word only breaks a word that does not fit on a line of its own.
        assertEquals(List.of("a", "aaa", "aaa", "aa"),
                runs(page, "word").stream().map(Fragment.TextRun::text).toList());
    }

    @Test
    void ellipsisReplacesOverflowingText() {
        Page page = new TestHost().load(
                "<div id=p style='width: 30px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis'>aaaaaaaaaa</div>");
        List<Fragment.TextRun> runs = runs(page, "p");
        assertEquals(1, runs.size());
        // "…" is 6 wide: four 6px letters fit before it.
        assertRun(runs.get(0), "aaaa…", 0, 0, 30);
        assertEquals(4, runs.get(0).sourceIndex(runs.get(0).text().length()));
    }

    @Test
    void lineClampKeepsNLinesAndEndsWithAnEllipsis() {
        Page page = new TestHost().load("<div id=p style='width: 20px; line-clamp: 2'>aaa aaa aaa aaa</div>");
        Box p = page.byId("p").box;
        assertEquals(2, p.lines.size());
        assertEquals(18, p.height, 0.01);
        List<Fragment.TextRun> runs = runs(p);
        assertRun(runs.get(0), "aaa", 0, 0, 18);
        // "aaa…" would be 24 wide; it is cut to fit the 20px line.
        assertRun(runs.get(1), "aa…", 0, 9, 18);
    }

    @Test
    void atomicInlinesAlignOnTheBaseline() {
        Page page = new TestHost().load(
                "<div id=p>a<span id=block style='display: inline-block; width: 10px; height: 20px'></span></div>");
        Box p = page.byId("p").box, block = page.byId("block").box;
        // No lines: the inline-block's bottom margin edge sits on the baseline.
        LineBox line = p.lines.get(0);
        assertEquals(22, line.height, 0.01);
        assertEquals(20, line.baseline, 0.01);
        assertRect(block, 6, 0, 10, 20);
        assertTrue(block.atomicInline);
        assertTrue(p.children.contains(block));
        assertInstanceOf(Fragment.Atomic.class, line.fragments.get(1));
        assertRun(runs(p).get(0), "a", 0, 13, 6);
    }

    @Test
    void inlineBlockBaselineIsItsLastLine() {
        Page page = new TestHost().load("<div id=p>a<span id=block style='display: inline-block; padding: 3px'>b</span>"
                + "<span id=scroller style='display: inline-block; padding: 3px; overflow: hidden'>c</span></div>");
        // The inline-block's baseline is 3 + 7 down; the clipping one aligns its bottom edge instead.
        assertRect(page.byId("block"), 6, 5, 12, 15);
        assertRect(page.byId("scroller"), 18, 0, 12, 15);
        assertEquals(20, page.byId("p").box.lines.get(0).height, 0.01);
        assertRun(runs(page, "p").get(0), "a", 0, 8, 6);
    }

    @Test
    void verticalAlignMiddleTopAndSuper() {
        Page page = new TestHost().load("<div id=p>a"
                + "<span id=middle style='display: inline-block; width: 10px; height: 20px; vertical-align: middle'></span>"
                + "<span id=top style='display: inline-block; width: 10px; height: 4px; vertical-align: top'></span>"
                + "<span style='vertical-align: super'>b</span></div>");
        // Middle: centred 2px (a quarter em) above the baseline, so its box spans 12 above to 8 below it.
        LineBox line = page.byId("p").box.lines.get(0);
        assertEquals(20, line.height, 0.01);
        assertEquals(12, line.baseline, 0.01);
        assertRect(page.byId("middle"), 6, 0, 10, 20);
        assertRect(page.byId("top"), 16, 0, 10, 4);
        // Super raises the baseline by 0.34em.
        assertRun(runs(page, "p").get(1), "b", 26, 12 - 2.72f - 7, 6);
    }

    @Test
    void pseudoElements() {
        Page page = new TestHost().load("""
                <style>
                  #p::before { content: '>'; padding-left: 2px }
                  #p::after { content: 'end'; display: block; height: 10px }
                </style>
                <div id=p>b</div>""");
        Element p = page.byId("p");
        // The inline ::before is generated text (no node) in the first anonymous line; ::after is a block box.
        Box anonymous = p.box.children.get(0);
        assertEquals(Box.Kind.ANONYMOUS, anonymous.kind);
        List<Fragment.TextRun> runs = runs(anonymous);
        assertRun(runs.get(0), ">", 2, 0, 5);
        assertNull(runs.get(0).node());
        assertEquals(p, runs.get(0).styleSource());
        assertRun(runs.get(1), "b", 7, 0, 6);
        Box after = p.box.children.get(1);
        assertEquals(Box.Kind.PSEUDO, after.kind);
        assertRect(after, 0, 9, 320, 10);
        assertEquals("end", runs(after).get(0).text());
        assertSame(p.box, anonymous.parent);
    }

    @Test
    void relativelyPositionedInlinesShiftTheirFragments() {
        Page page = new TestHost().load(
                "<div id=p><span id=span style='position: relative; top: 2px; left: 3px'>a</span></div>");
        assertRun(runs(page, "p").get(0), "a", 3, 2, 6);
        assertRect(page.byId("span"), 3, 2, 6, 9);
    }

    @Test
    void inlineContainingABlockIsBlockified() {
        Page page = new TestHost().load("<span id=span>a<div id=inner style='height: 5px'></div></span>");
        assertEquals(Box.Kind.BLOCK, page.byId("span").box.kind);
        assertRect(page.byId("span"), 0, 0, 320, 14);
        assertRect(page.byId("inner"), 0, 9, 320, 5);
    }
}
