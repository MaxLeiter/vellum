package dev.vellum.engine.input;

import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TooltipTest {
    /** a (0,0 100x20) has a title and holds b (0,0 50x20); c (0,20 100x20) has rich text; d has an empty title in a. */
    private final Page page = new TestHost().load("""
            <div id=a title="Line one&#10;Line two" style="display: flex; height: 20px; width: 100px">
              <span id=b style="width: 50px"></span><span id=d title="" style="width: 50px"></span>
            </div>
            <div id=c title-json='{"text":"Gold","color":"gold"}' title="Gold" style="height: 20px; width: 100px"></div>
            <div id=e style="height: 20px; width: 100px"></div>""");

    /** Runs a frame at {@code ms} and asks for the tooltip, as hosts do every frame. */
    private Tooltip at(double ms) {
        page.frame(ms);
        return page.input.tooltip();
    }

    @Test
    void showsAfterTheDelayAtThePointer() {
        page.move(10, 10);
        assertNull(at(400), "not yet");
        assertTrue(page.doc.needsFrame(500), "the frame that shows it is needed");
        Tooltip t = at(500);
        assertSame(page.byId("a"), t.element(), "the nearest element with a title, from the hovered one up");
        assertEquals("Line one\nLine two", t.text());
        assertNull(t.json());
        assertEquals(10, t.x());
        assertEquals(10, t.y());
        page.paint();
        assertFalse(page.doc.needsFrame(600), "given to the host: no more frames needed for it");
        page.move(20, 12);
        assertEquals(20, at(616).x(), "follows the pointer, without a new delay inside the same element");
    }

    @Test
    void richTextComesWithItsPlainFallback() {
        page.move(10, 30);
        Tooltip t = at(500);
        assertEquals("{\"text\":\"Gold\",\"color\":\"gold\"}", t.json());
        assertEquals("Gold", t.text());
    }

    @Test
    void anEmptyTitleHidesTheAncestorsAndAnotherElementRestartsTheDelay() {
        page.move(10, 10);
        assertTrue(at(500) != null);
        page.move(60, 10);
        assertNull(at(1500), "d's empty title means no tooltip");
        page.move(10, 30);
        assertNull(at(1600), "c's delay starts when the pointer reaches it");
        assertSame(page.byId("c"), at(2100).element());
        page.move(10, 50);
        assertNull(at(3000), "e has none");
    }

    @Test
    void pressingHidesItUntilThePointerMovesToAnotherElement() {
        page.move(10, 10);
        assertTrue(at(500) != null);
        page.down(10, 10);
        page.up(10, 10);
        assertNull(at(2000));
        page.move(10, 30);
        page.move(10, 10);
        assertTrue(at(3000) != null, "back after leaving and resting again");
        page.key("x");
        assertNull(at(4000), "keys hide it too");
    }

    @Test
    void scriptsChangeTheTextLive() {
        page.move(10, 50);
        assertNull(at(500));
        page.byId("e").setAttribute("title", "Now");
        assertNull(at(516), "an element that gains a title starts its delay");
        assertEquals("Now", at(1016).text());
        page.byId("e").setAttribute("title", "Later");
        assertEquals("Later", at(1032).text(), "the text is read when the host asks");
    }

    @Test
    void leavingEndsHoverAndTheTooltip() {
        page.listen(page.byId("a"), "mouseleave");
        page.move(10, 10);
        assertTrue(at(500) != null);
        page.input.mouseLeave();
        assertEquals(List.of("mouseleave:a"), page.takeLog());
        assertFalse(page.byId("a").isHovered());
        assertNull(at(2000));
        page.paint();
        assertFalse(page.doc.needsFrame(3000));
    }
}
