package dev.vellum.engine.paint;

import dev.vellum.engine.layout.Box;
import dev.vellum.engine.style.Overflow;
import org.junit.jupiter.api.Test;

import static dev.vellum.engine.paint.TestTree.scroller;
import static dev.vellum.engine.paint.TestTree.style;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScrollbarsTest {
    private final TestTree t = new TestTree();
    private final Box box = t.div(0, 0, 100, 50, 0);

    {
        box.borderTop = box.borderRight = box.borderBottom = box.borderLeft = 1;
        scroller(box, Overflow.AUTO, 0, 0, 98, 200);
    }

    @Test
    void verticalTrackRunsDownThePaddingBoxEdge() {
        assertArrayEquals(new float[] {97, 1, 2, 48}, Scrollbars.track(box, true, false), 1e-5f);
        assertArrayEquals(new float[] {95, 1, 4, 48}, Scrollbars.track(box, true, true), 1e-5f);
        assertNull(Scrollbars.track(box, false, false), "no horizontal overflow");
    }

    @Test
    void thumbIsProportionalAndFollowsTheScrollOffset() {
        // 48 visible of 200: a 11.52px thumb
        assertArrayEquals(new float[] {97, 1, 2, 48 * 48 / 200f}, Scrollbars.thumb(box, true, false), 1e-4f);
        box.element.scrollTo(0, 152); // the end
        float[] thumb = Scrollbars.thumb(box, true, false);
        assertEquals(1 + 48, thumb[1] + thumb[3], 1e-4);
        assertEquals(152 / (48 - 11.52f), Scrollbars.scrollPerThumbPixel(box, true), 1e-3);
    }

    @Test
    void thumbsHaveAMinimumLength() {
        box.scrollHeight = 10_000;
        assertEquals(Scrollbars.MIN_THUMB, Scrollbars.thumb(box, true, false)[3], 1e-5);
    }

    @Test
    void thinAndNoneWidths() {
        style(box).scrollbarWidth = 1;
        assertEquals(1, Scrollbars.track(box, true, false)[2], 1e-5);
        assertEquals(2, Scrollbars.track(box, true, true)[2], 1e-5);
        style(box).scrollbarWidth = 0;
        assertNull(Scrollbars.track(box, true, false));
        assertEquals(0, Scrollbars.scrollPerThumbPixel(box, true));
    }

    @Test
    void bothBarsLeaveTheCornerFree() {
        box.scrollWidth = 300;
        assertArrayEquals(new float[] {97, 1, 2, 46}, Scrollbars.track(box, true, false), 1e-5f);
        assertArrayEquals(new float[] {1, 47, 96, 2}, Scrollbars.track(box, false, false), 1e-5f);
    }

    @Test
    void hiddenOverflowHasNoBars() {
        scroller(box, Overflow.HIDDEN, 0, 0, 98, 200);
        assertNull(Scrollbars.thumb(box, true, false));
    }
}
