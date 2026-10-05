package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.paint.Canvas;
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
        assertNull(t.content());
        assertTrue(t.wrap(), "title tooltips wrap");
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
        page.paint();
        assertFalse(page.doc.needsFrame(1600), "and no frame waits for one");
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
    void movingOffThePageEndsHoverAndTheTooltip() {
        page.move(10, 10);
        assertTrue(at(500) != null);
        assertFalse(page.move(-16, -16), "nothing is out there");
        assertFalse(page.byId("a").isHovered());
        assertNull(at(2000));
        page.paint();
        assertTrue(page.doc.settled());
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

    @Test
    void titleNowrapKeepsTheLinesWhole() {
        page.byId("a").setAttribute("title-nowrap", "");
        page.move(10, 10);
        assertFalse(at(500).wrap());
    }

    // ---- Content with a tooltip of its own: <item tooltip> ----

    /** An item as Minecraft's: its tooltip shows while hovered when it has the {@code tooltip} attribute. */
    private record Item(Element element) implements ReplacedContent {
        @Override public float intrinsicWidth() { return 16; }
        @Override public float intrinsicHeight() { return 16; }
        @Override public void paint(Canvas canvas, float x, float y, float width, float height) {}
        @Override public boolean showsTooltip() { return element.hasAttribute("tooltip"); }
    }

    /**
     * Shop rows: #row has a title-json, and the item #sword inside it shows its own tooltip; in #quiet, #muted has an
     * empty title and #icon no tooltip of its own; #bare has no title above it.
     */
    private static Page shop() {
        TestHost host = new TestHost();
        host.replaced.put("item", Item::new);
        return host.load("""
                <style>div { display: flex; height: 16px } span { width: 60px }</style>
                <div id=row title-json='{"text":"Buy for 6 emeralds","color":"green"}'>
                  <item id=sword tooltip></item><span id=name>Iron Sword</span>
                </div>
                <div id=quiet title="Quiet"><item id=muted tooltip title=""></item><item id=icon></item></div>
                <div><item id=bare tooltip></item></div>""");
    }

    @Test
    void anItemsTooltipShowsAtOnceWithTheTitleLinesAfterIt() {
        Page shop = shop();
        shop.hover(shop.byId("sword"));
        shop.frame(16);
        Tooltip t = shop.input.tooltip();
        assertSame(shop.byId("sword"), t.content(), "the item's own tooltip, with no delay");
        assertSame(shop.byId("row"), t.element(), "the row's title adds its lines");
        assertEquals("{\"text\":\"Buy for 6 emeralds\",\"color\":\"green\"}", t.json());
        assertFalse(t.wrap(), "lines after an item's tooltip don't wrap, as an item's own lines don't");
        shop.paint();
        assertTrue(shop.doc.settled(), "nothing is waiting out a delay");
        assertFalse(shop.doc.needsFrame(1000));
    }

    @Test
    void besideTheItemTheTitleShowsAloneAfterTheDelay() {
        Page shop = shop();
        shop.hover(shop.byId("name"));
        shop.frame(400);
        assertNull(shop.input.tooltip());
        shop.frame(516);
        Tooltip t = shop.input.tooltip();
        assertSame(shop.byId("row"), t.element());
        assertNull(t.content());
        assertTrue(t.wrap());
        shop.hover(shop.byId("sword"));
        assertSame(shop.byId("sword"), shop.input.tooltip().content(), "and the item's comes back at once");
    }

    @Test
    void pressingKeepsAnItemsTooltip() {
        Page shop = shop();
        shop.click(shop.byId("sword"));
        shop.key("x");
        shop.frame(1000);
        assertSame(shop.byId("sword"), shop.input.tooltip().content(), "as Minecraft's item tooltips stay up");
    }

    @Test
    void anEmptyTitleOnTheItemOrNoneAboveLeavesItsTooltipAlone() {
        Page shop = shop();
        for (String id : new String[] {"muted", "bare"}) {
            shop.hover(shop.byId(id));
            Tooltip t = shop.input.tooltip();
            assertSame(shop.byId(id), t.content(), id);
            assertNull(t.element(), id + ": no title applies");
            assertNull(t.text());
            assertNull(t.json());
        }
    }

    @Test
    void contentWithoutATooltipOfItsOwnGetsTheTitleAsAnyElement() {
        Page shop = shop();
        shop.hover(shop.byId("icon"));
        shop.frame(100);
        assertNull(shop.input.tooltip(), "the title's delay, as for any element");
        shop.byId("icon").setAttribute("tooltip", "");
        assertSame(shop.byId("icon"), shop.input.tooltip().content(), "the attribute is read when the host asks");
        assertEquals("Quiet", shop.input.tooltip().text());
    }
}
