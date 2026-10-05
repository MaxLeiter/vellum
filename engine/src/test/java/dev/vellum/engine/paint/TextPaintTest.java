package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.layout.Fragment;
import dev.vellum.engine.layout.LineBox;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Layout, painting and hit testing place glyphs alike (one advance rule: the host's glyphs plus letter- and
 * word-spacing), and caret offsets map back to the text node's data.
 */
class TextPaintTest {
    private static Document page(String body) {
        return new TestHost().load("<body>" + body + "</body>");
    }

    private static List<RecordingCanvas.Call> texts(Document doc) {
        RecordingCanvas canvas = new RecordingCanvas();
        doc.paint(canvas);
        return canvas.ops("drawText");
    }

    private static Fragment.TextRun firstRun(Element e) {
        for (LineBox line : e.box.lines) for (Fragment f : line.fragments) if (f instanceof Fragment.TextRun r) return r;
        throw new AssertionError("no text in " + e);
    }

    @Test
    void wordSpacingIsPaintedAndHitWhereLayoutPutIt() {
        Document doc = page("<p id=p style='margin: 0; word-spacing: 4px'>ab cd</p>");
        Element p = doc.getElementById("p");
        // a 6, b 6, space 4 + 4, c 6, d 6
        assertEquals(32, firstRun(p).width(), 1e-3);
        List<RecordingCanvas.Call> texts = texts(doc);
        assertEquals(List.of("ab ", "cd"), texts.stream().map(RecordingCanvas.Call::text).toList());
        assertEquals(20, texts.get(1).x(), 1e-3, "after the widened space");
        assertEquals(3, doc.hitTest(22, 4).textOffset(), "left half of the c: before it");
        assertEquals(4, doc.hitTest(24, 4).textOffset());
    }

    @Test
    void letterSpacedTextIsCutOnceNotEveryFrame() {
        Document doc = page("<p style='margin: 0; letter-spacing: 1px'>ab</p>");
        List<RecordingCanvas.Call> first = texts(doc), second = texts(doc);
        assertEquals(List.of("a", "b"), first.stream().map(RecordingCanvas.Call::text).toList());
        assertEquals(7, first.get(1).x(), 1e-3, "a's 6 plus the spacing");
        assertSame(first.get(1).text(), second.get(1).text(), "the same glyph string, drawn again");
        assertEquals(1, doc.hitTest(8, 4).textOffset());
    }

    @Test
    void caretOffsetsMapBackThroughCollapsedWhiteSpace() {
        Document doc = page("<p id=p style='margin: 0'>  aaa   bbb</p>");
        // Laid out as "aaa bbb"; the second b is char 5 of the run but char 9 of the node's data.
        HitResult hit = doc.hitTest(6 * 3 + 4 + 6 + 1, 4);
        assertSame(doc.getElementById("p").firstChild(), hit.text());
        assertEquals(9, hit.textOffset());
    }
}
