package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.vellum.engine.layout.BlockLayoutTest.assertRect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    static void assertRun(Fragment.TextRun run, String text, float x, float y, float width) {
        assertEquals(text, run.text());
        assertEquals(x, run.x(), 0.01, "x of '" + text + "'");
        assertEquals(y, run.y(), 0.01, "y of '" + text + "'");
        assertEquals(width, run.width(), 0.01, "width of '" + text + "'");
    }

    @Test
    void singleLineOfText() {
        TestDoc t = new TestDoc();
        Element p = t.div(t.body, "");
        t.text(p, "Hello world");
        t.layout();
        assertRect(p, 0, 0, 320, 9);
        List<Fragment.TextRun> runs = runs(p.box);
        assertEquals(1, runs.size());
        // H e l l o = 6+6+3+3+6, space 4, w o r l d = 6+6+6+3+6
        assertRun(runs.get(0), "Hello world", 0, 0, 55);
        LineBox line = p.box.lines.get(0);
        assertEquals(7, line.baseline, 0.01);
        assertEquals(7, p.box.baseline, 0.01);
        assertEquals(0, runs.get(0).start());
        assertEquals(11, runs.get(0).end());
    }

    @Test
    void wrapsAtSpacesAndStripsCollapsibleWhitespace() {
        TestDoc t = new TestDoc();
        Element p = t.div(t.body, "width: 39px");
        t.text(p, "  aaa   bbb\n ccc  ");
        t.layout();
        List<Fragment.TextRun> runs = runs(p.box);
        assertEquals(3, p.box.lines.size());
        assertRun(runs.get(0), "aaa", 0, 0, 18);
        assertRun(runs.get(1), "bbb", 0, 9, 18);
        assertRun(runs.get(2), "ccc", 0, 18, 18);
        assertRect(p, 0, 0, 39, 27);
        // Source offsets map back into the text node across collapsed white space.
        assertEquals(2, runs.get(0).start());
        assertEquals(8, runs.get(1).start());
        assertEquals(13, runs.get(2).start());
    }

    @Test
    void twoWordsFitOnALine() {
        TestDoc t = new TestDoc();
        Element p = t.div(t.body, "width: 40px");
        t.text(p, "aaa bbb");
        t.layout();
        assertEquals(1, p.box.lines.size());
        assertRun(runs(p.box).get(0), "aaa bbb", 0, 0, 40);
    }

    @Test
    void textAlign() {
        TestDoc t = new TestDoc();
        Element c = t.div(t.body, "width: 100px; text-align: center");
        t.text(c, "aaa");
        Element r = t.div(t.body, "width: 100px; text-align: right");
        t.text(r, "aaa");
        t.layout();
        assertRun(runs(c.box).get(0), "aaa", 41, 0, 18);
        assertRun(runs(r.box).get(0), "aaa", 82, 0, 18);
    }

    @Test
    void lineHeightAddsHalfLeading() {
        TestDoc t = new TestDoc();
        Element p = t.div(t.body, "line-height: 20px");
        t.text(p, "a");
        t.layout();
        assertRect(p, 0, 0, 320, 20);
        assertRun(runs(p.box).get(0), "a", 0, 5.5f, 6);
        assertEquals(12.5f, p.box.lines.get(0).baseline, 0.01);
    }

    @Test
    void inlineBoxesWithEdges() {
        TestDoc t = new TestDoc();
        Element p = t.div(t.body, "");
        t.text(p, "a");
        Element span = t.add(p, "span", "padding: 2px 3px; margin-left: 1px; border: 1px");
        t.text(span, "bb");
        t.text(p, "c");
        t.layout();
        List<Fragment> frags = p.box.lines.get(0).fragments;
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
        assertEquals(Box.Kind.INLINE, span.box.kind);
        assertRect(span.box, 7, -3, 20, 15);
        assertFalse(p.box.children.contains(span.box));
    }

    @Test
    void justifyStretchesSpacesExceptOnTheLastLine() {
        TestDoc t = new TestDoc();
        Element p = t.div(t.body, "width: 40px; text-align: justify");
        t.text(p, "aa bb cc dd");
        t.layout();
        List<Fragment.TextRun> runs = runs(p.box);
        // Line 1 "aa bb" is 28 wide: its one space takes the 12px left over.
        assertRun(runs.get(0), "aa", 0, 0, 12);
        assertRun(runs.get(1), "bb", 28, 0, 12);
        assertRun(runs.get(2), "cc dd", 0, 9, 28);
    }

    @Test
    void forcedBreaksAndPreservedWhiteSpace() {
        TestDoc t = new TestDoc();
        Element p = t.div(t.body, "width: 100px; text-align: justify");
        t.text(p, "aa bb");
        Element br = t.add(p, "br", "");
        t.text(p, " cc");
        Element pre = t.div(t.body, "white-space: pre");
        t.text(pre, "a  b\nc\n");
        t.layout();
        List<Fragment.TextRun> runs = runs(p.box);
        // A line ending in a forced break is not justified; the space after the break is stripped.
        assertRun(runs.get(0), "aa bb", 0, 0, 28);
        assertRun(runs.get(1), "cc", 0, 9, 12);
        assertEquals(Box.Kind.INLINE, br.box.kind);
        assertEquals(28, br.box.x, 0.01);
        List<Fragment.TextRun> preRuns = runs(pre.box);
        assertRun(preRuns.get(0), "a  b", 0, 0, 20);
        assertRun(preRuns.get(1), "c", 0, 9, 6);
        // The final newline ends the last line without starting another.
        assertEquals(18, pre.box.height, 0.01);
    }

    @Test
    void spacingIndentAndTransform() {
        TestDoc t = new TestDoc();
        Element a = t.div(t.body, "letter-spacing: 1px");
        t.text(a, "ab");
        Element b = t.div(t.body, "word-spacing: 2px; text-indent: 10px");
        t.text(b, "a b");
        Element c = t.div(t.body, "text-transform: uppercase");
        t.text(c, "ab");
        Element d = t.div(t.body, "text-transform: capitalize");
        t.text(d, "hello (world)");
        t.layout();
        assertRun(runs(a.box).get(0), "ab", 0, 0, 14);
        assertRun(runs(b.box).get(0), "a b", 10, 0, 18);
        assertEquals("AB", runs(c.box).get(0).text());
        assertEquals("Hello (World)", runs(d.box).get(0).text());
    }

    @Test
    void breakAllAndBreakWordSplitLongWords() {
        TestDoc t = new TestDoc();
        Element normal = t.div(t.body, "width: 20px");
        t.text(normal, "aaaaaaaa");
        Element all = t.div(t.body, "width: 20px; word-break: break-all");
        t.text(all, "aaaaaaaa");
        Element word = t.div(t.body, "width: 20px; overflow-wrap: break-word");
        t.text(word, "a aaaaaaaa");
        t.layout();
        assertEquals(1, normal.box.lines.size(), "overflows instead of breaking");
        List<Fragment.TextRun> runs = runs(all.box);
        assertEquals(List.of("aaa", "aaa", "aa"), runs.stream().map(Fragment.TextRun::text).toList());
        // break-word only breaks a word that does not fit on a line of its own.
        assertEquals(List.of("a", "aaa", "aaa", "aa"), runs(word.box).stream().map(Fragment.TextRun::text).toList());
    }

    @Test
    void ellipsisReplacesOverflowingText() {
        TestDoc t = new TestDoc();
        Element p = t.div(t.body, "width: 30px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis");
        t.text(p, "aaaaaaaaaa");
        t.layout();
        List<Fragment.TextRun> runs = runs(p.box);
        assertEquals(1, runs.size());
        // "…" is 6 wide: four 6px letters fit before it.
        assertRun(runs.get(0), "aaaa\u2026", 0, 0, 30);
        assertEquals(4, runs.get(0).end());
    }

    @Test
    void lineClampKeepsNLinesAndEndsWithAnEllipsis() {
        TestDoc t = new TestDoc();
        Element p = t.div(t.body, "width: 20px; line-clamp: 2");
        t.text(p, "aaa aaa aaa aaa");
        t.layout();
        assertEquals(2, p.box.lines.size());
        assertEquals(18, p.box.height, 0.01);
        List<Fragment.TextRun> runs = runs(p.box);
        assertRun(runs.get(0), "aaa", 0, 0, 18);
        // "aaa…" would be 24 wide; it is cut to fit the 20px line.
        assertRun(runs.get(1), "aa\u2026", 0, 9, 18);
    }

    @Test
    void atomicInlinesAlignOnTheBaseline() {
        TestDoc t = new TestDoc();
        Element p = t.div(t.body, "");
        t.text(p, "a");
        Element block = t.add(p, "span", "display: inline-block; width: 10px; height: 20px");
        t.layout();
        // No lines: the inline-block's bottom margin edge sits on the baseline.
        LineBox line = p.box.lines.get(0);
        assertEquals(22, line.height, 0.01);
        assertEquals(20, line.baseline, 0.01);
        assertRect(block, 6, 0, 10, 20);
        assertTrue(block.box.atomicInline);
        assertTrue(p.box.children.contains(block.box));
        assertInstanceOf(Fragment.Atomic.class, line.fragments.get(1));
        assertRun(runs(p.box).get(0), "a", 0, 13, 6);
    }

    @Test
    void inlineBlockBaselineIsItsLastLine() {
        TestDoc t = new TestDoc();
        Element p = t.div(t.body, "");
        t.text(p, "a");
        Element block = t.add(p, "span", "display: inline-block; padding: 3px");
        t.text(block, "b");
        Element scroller = t.add(p, "span", "display: inline-block; padding: 3px; overflow: hidden");
        t.text(scroller, "c");
        t.layout();
        // The inline-block's baseline is 3 + 7 down; the clipping one aligns its bottom edge instead.
        assertRect(block, 6, 5, 12, 15);
        assertRect(scroller, 18, 0, 12, 15);
        assertEquals(20, p.box.lines.get(0).height, 0.01);
        assertRun(runs(p.box).get(0), "a", 0, 8, 6);
    }

    @Test
    void verticalAlignMiddleTopAndSuper() {
        TestDoc t = new TestDoc();
        Element p = t.div(t.body, "");
        t.text(p, "a");
        Element middle = t.add(p, "span", "display: inline-block; width: 10px; height: 20px; vertical-align: middle");
        Element top = t.add(p, "span", "display: inline-block; width: 10px; height: 4px; vertical-align: top");
        Element sup = t.add(p, "span", "vertical-align: super");
        t.text(sup, "b");
        t.layout();
        // Middle: centred 2px (a quarter em) above the baseline, so its box spans 12 above to 8 below it.
        LineBox line = p.box.lines.get(0);
        assertEquals(20, line.height, 0.01);
        assertEquals(12, line.baseline, 0.01);
        assertRect(middle, 6, 0, 10, 20);
        assertRect(top, 16, 0, 10, 4);
        // Super raises the baseline by 0.34em.
        assertRun(runs(p.box).get(1), "b", 26, 12 - 2.72f - 7, 6);
    }

    @Test
    void pseudoElements() {
        TestDoc t = new TestDoc();
        Element p = t.div(t.body, "");
        t.text(p, "b");
        t.before(p, "content: '>'; padding-left: 2px");
        t.after(p, "content: 'end'; display: block; height: 10px");
        t.layout();
        // The inline ::before is generated text (no node) in the first anonymous line; ::after is a block box.
        Box anonymous = p.box.children.get(0);
        assertEquals(Box.Kind.ANONYMOUS, anonymous.kind);
        List<Fragment.TextRun> runs = runs(anonymous);
        assertRun(runs.get(0), ">", 2, 0, 5);
        assertEquals(null, runs.get(0).node());
        assertEquals(p, runs.get(0).styleSource());
        assertRun(runs.get(1), "b", 7, 0, 6);
        Box after = p.box.children.get(1);
        assertEquals(Box.Kind.PSEUDO, after.kind);
        assertRect(after, 0, 9, 320, 10);
        assertEquals("end", runs(after).get(0).text());
        assertEquals(p.box, p.box.children.get(0).parent);
    }

    @Test
    void relativelyPositionedInlinesShiftTheirFragments() {
        TestDoc t = new TestDoc();
        Element p = t.div(t.body, "");
        Element span = t.add(p, "span", "position: relative; top: 2px; left: 3px");
        t.text(span, "a");
        t.layout();
        assertRun(runs(p.box).get(0), "a", 3, 2, 6);
        assertRect(span, 3, 2, 6, 9);
    }

    @Test
    void inlineContainingABlockIsBlockified() {
        TestDoc t = new TestDoc();
        Element span = t.add(t.body, "span", "");
        t.text(span, "a");
        Element inner = t.div(span, "height: 5px");
        t.layout();
        assertEquals(Box.Kind.BLOCK, span.box.kind);
        assertRect(span, 0, 0, 320, 14);
        assertRect(inner, 0, 9, 320, 5);
    }
}
