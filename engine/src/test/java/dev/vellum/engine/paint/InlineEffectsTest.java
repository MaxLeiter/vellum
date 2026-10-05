package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.RecordingCanvas;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An inline element's fragments are painted as a group: its opacity and outline apply to them. */
class InlineEffectsTest {

    @Test
    void opacityAppliesToTheInlineElementsFragments() {
        Page page = new TestHost().load("<p style='margin: 0'>a <span style='opacity: 0.5; background: #00f'>b <i>c</i></span> d</p>");
        RecordingCanvas c = page.paint();
        List<String> texts = c.ops("drawText").stream().map(call -> call.text() + "@" + call.alpha()).toList();
        assertEquals(List.of("a @1.0", "b @0.5", "c@0.5", " d@1.0"), texts);
        assertEquals(0.5f, c.ops("fillRect").stream().filter(f -> f.color() == 0xFF0000FF).findFirst().orElseThrow().alpha());
        assertTrue(c.balanced());
        assertEquals(List.of("a "), new TestHost().load("<p style='margin: 0'>a <span style='opacity: 0'>b</span></p>").paint()
                .ops("drawText").stream().map(RecordingCanvas.Call::text).toList(), "transparent: skipped");
    }

    @Test
    void focusedLinksGetTheirFocusRing() {
        Page page = new TestHost().load("<p style='margin: 0'>go <a id=a href=x>there</a></p>");
        Element a = page.byId("a");
        assertEquals(0, page.paint().ops("fillBorder").size());
        page.key("Tab");
        page.frame();
        assertSame(a, page.doc.focusedElement());
        RecordingCanvas.Call ring = page.paint().ops("fillBorder").getFirst();
        float[] link = a.getBoundingClientRect();
        // outline: 1px solid #fff, offset 1px: two pixels out all round.
        assertArrayEquals(new float[] {link[0] - 2, link[1] - 2, link[2] + 4, link[3] + 4}, ring.bounds(), 1e-3f);
        assertEquals(0xFFFFFFFF, ring.color());
    }

    @Test
    void hitsOnInlineContentReportTheInlineBox() {
        Page page = new TestHost().load("<p style='margin: 0'>go <b id=b>bold</b></p>");
        Element b = page.byId("b");
        HitResult hit = page.doc.hitTest(b.getBoundingClientRect()[0] + 2, 3);
        assertSame(b, hit.element());
        assertSame(b.box, hit.box());
        assertEquals(2, hit.localX(), 1e-3);
    }
}
