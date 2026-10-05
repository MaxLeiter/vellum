package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Scrollbar geometry of a 100x50 scroller with a 1px border (padding box 98x48) over 200px of content. */
class ScrollbarsTest {
    private static final String SCROLLER = "width: 100px; height: 50px; border: 1px solid; overflow: auto";
    private final Page page =
            new TestHost().load("<div id=s style='" + SCROLLER + "'><div id=content style='height: 200px'></div></div>");
    private final Element scroller = page.byId("s");

    private Box box() {
        return scroller.box;
    }

    private void restyle(String id, String css) {
        page.byId(id).setAttribute("style", css);
        page.frame();
    }

    private float[] track(boolean vertical, boolean hovered) {
        float[] r = new float[4];
        return Scrollbars.track(box(), vertical, hovered, r) ? r : null;
    }

    @Test
    void verticalTrackRunsDownThePaddingBoxEdge() {
        assertArrayEquals(new float[] {97, 1, 2, 48}, track(true, false), 1e-5f);
        assertArrayEquals(new float[] {95, 1, 4, 48}, track(true, true), 1e-5f);
        assertNull(track(false, false), "no horizontal overflow");
    }

    @Test
    void thumbIsProportionalAndFollowsTheScrollOffset() {
        // 48 visible of 200: a 11.52px thumb
        assertArrayEquals(new float[] {97, 1, 2, 48 * 48 / 200f}, Scrollbars.thumb(box(), true, false), 1e-4f);
        scroller.scrollTo(0, 152); // the end
        float[] thumb = Scrollbars.thumb(box(), true, false);
        assertEquals(1 + 48, thumb[1] + thumb[3], 1e-4);
        assertEquals(152 / (48 - 11.52f), Scrollbars.scrollPerThumbPixel(box(), true), 1e-3);
    }

    @Test
    void thumbsHaveAMinimumLength() {
        restyle("content", "height: 10000px");
        assertEquals(Scrollbars.MIN_THUMB, Scrollbars.thumb(box(), true, false)[3], 1e-5);
    }

    @Test
    void thinAndNoneWidths() {
        restyle("s", SCROLLER + "; scrollbar-width: thin");
        assertEquals(1, track(true, false)[2], 1e-5);
        assertEquals(2, track(true, true)[2], 1e-5);
        restyle("s", SCROLLER + "; scrollbar-width: none");
        assertNull(track(true, false));
        assertEquals(0, Scrollbars.scrollPerThumbPixel(box(), true));
    }

    @Test
    void bothBarsLeaveTheCornerFree() {
        restyle("content", "height: 200px; width: 300px");
        assertArrayEquals(new float[] {97, 1, 2, 46}, track(true, false), 1e-5f);
        assertArrayEquals(new float[] {1, 47, 96, 2}, track(false, false), 1e-5f);
    }

    @Test
    void hiddenOverflowHasNoBars() {
        restyle("s", SCROLLER + "; overflow: hidden");
        assertNull(Scrollbars.thumb(box(), true, false));
    }
}
