package dev.vellum.engine.paint;

import dev.vellum.engine.layout.Box;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Overflow;

/**
 * Geometry of the overlay scrollbars of a scroll container, shared by the painter (drawing) and the input handler
 * (hover, drag, track clicks). Rectangles are {x, y, width, height} in the box's border-box coordinates, or null when
 * that axis does not scroll.
 *
 * <p>An axis has a scrollbar when its overflow is {@code scroll} or {@code auto}, its content overflows, and
 * {@code scrollbar-width} is not {@code none}. The vertical track runs down the right edge of the padding box, the
 * horizontal one along the bottom (stopping short of the other bar when both show). Bars are {@value #WIDTH}px wide,
 * {@value #HOVER_WIDTH}px while the container is hovered ({@code thin}: {@value #THIN_WIDTH}/{@value
 * #THIN_HOVER_WIDTH}px). The thumb's length is proportional to the visible fraction, at least {@value #MIN_THUMB}px.
 *
 * <p>The {@code out} overloads write into a caller array and return false instead of null, for per-frame use.
 */
public final class Scrollbars {
    public static final float WIDTH = 2, HOVER_WIDTH = 4, THIN_WIDTH = 1, THIN_HOVER_WIDTH = 2, MIN_THUMB = 8;

    private Scrollbars() {}

    /** The track along the right (vertical) or bottom (horizontal) edge of the padding box. */
    public static float[] track(Box box, boolean vertical, boolean hovered) {
        float[] r = new float[4];
        return track(box, vertical, hovered, r) ? r : null;
    }

    /** The thumb within the track for the current scroll offset. */
    public static float[] thumb(Box box, boolean vertical, boolean hovered) {
        float[] r = new float[4];
        return thumb(box, vertical, hovered, r) ? r : null;
    }

    public static boolean track(Box box, boolean vertical, boolean hovered, float[] out) {
        ComputedStyle s = StackingOrder.styleOf(box);
        if (!hasBar(box, s, vertical)) return false;
        float size = barWidth(s, hovered);
        float other = hasBar(box, s, !vertical) ? size : 0;
        float pw = box.paddingBoxWidth(), ph = box.paddingBoxHeight();
        if (vertical) {
            set(out, box.borderLeft + pw - size, box.borderTop, size, Math.max(0, ph - other));
        } else {
            set(out, box.borderLeft, box.borderTop + ph - size, Math.max(0, pw - other), size);
        }
        return true;
    }

    public static boolean thumb(Box box, boolean vertical, boolean hovered, float[] out) {
        if (!track(box, vertical, hovered, out)) return false;
        float trackLength = vertical ? out[3] : out[2];
        float thumb = thumbLength(box, vertical, trackLength);
        float max = maxScroll(box, vertical);
        float scroll = vertical ? box.scrollTop() : box.scrollLeft();
        float offset = max > 0 ? (trackLength - thumb) * Math.clamp(scroll / max, 0, 1) : 0;
        if (vertical) {
            out[1] += offset;
            out[3] = thumb;
        } else {
            out[0] += offset;
            out[2] = thumb;
        }
        return true;
    }

    /** Scroll offset change per px of thumb movement along the axis. */
    public static float scrollPerThumbPixel(Box box, boolean vertical) {
        ComputedStyle s = StackingOrder.styleOf(box);
        if (!hasBar(box, s, vertical)) return 0;
        // The track length does not depend on hover except where the bars meet; use the resting width.
        float other = hasBar(box, s, !vertical) ? barWidth(s, false) : 0;
        float trackLength = Math.max(0, (vertical ? box.paddingBoxHeight() : box.paddingBoxWidth()) - other);
        float travel = trackLength - thumbLength(box, vertical, trackLength);
        return travel > 0 ? maxScroll(box, vertical) / travel : 0;
    }

    private static boolean hasBar(Box box, ComputedStyle s, boolean vertical) {
        if (!box.isScrollContainer() || s.scrollbarWidth == 0) return false;
        Overflow overflow = vertical ? s.overflowY : s.overflowX;
        return (overflow == Overflow.SCROLL || overflow == Overflow.AUTO) && maxScroll(box, vertical) > 0.5f;
    }

    private static float barWidth(ComputedStyle s, boolean hovered) {
        if (s.scrollbarWidth == 1) return hovered ? THIN_HOVER_WIDTH : THIN_WIDTH;
        return hovered ? HOVER_WIDTH : WIDTH;
    }

    private static float maxScroll(Box box, boolean vertical) {
        return vertical ? box.maxScrollTop() : box.maxScrollLeft();
    }

    private static float thumbLength(Box box, boolean vertical, float trackLength) {
        float client = vertical ? box.paddingBoxHeight() : box.paddingBoxWidth();
        float content = vertical ? box.scrollHeight : box.scrollWidth;
        return Math.clamp(trackLength * client / content, Math.min(MIN_THUMB, trackLength), trackLength);
    }

    private static void set(float[] out, float x, float y, float w, float h) {
        out[0] = x;
        out[1] = y;
        out[2] = w;
        out[3] = h;
    }
}
