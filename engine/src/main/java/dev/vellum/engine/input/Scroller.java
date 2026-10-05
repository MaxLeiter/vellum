package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Element.ScrollBehavior;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.paint.HitResult;
import dev.vellum.engine.paint.Scrollbars;
import dev.vellum.engine.style.Overflow;

/**
 * User scrolling of scroll containers: the wheel with scroll chaining, and the overlay scrollbars (hover, thumb
 * drag, track paging). Scrolling itself, smooth or not, is the element's ({@link Element#scrollTo}).
 */
final class Scroller {
    /** Fraction of the visible size that a click on the scrollbar track pages by. */
    private static final float PAGE = 0.875f;

    /** The hovered (or dragged) scrollbar: its container's element and axis, or null. */
    private Element hovered;
    private boolean hoveredVertical;

    /**
     * Scrolls the nearest scroll container whose content holds {@code start} that can still move by (dx, dy) along an
     * axis users may scroll ({@code overflow: scroll | auto}), with its {@code scroll-behavior}. True if one did.
     */
    boolean wheel(Element start, float dx, float dy) {
        Element e = Dom.closest(start, p -> p.box != null);
        for (Box c = e == null ? null : e.box; c != null; c = c.contentParent()) {
            if (!c.isScrollContainer()) continue;
            float mx = userScrollable(c.style.overflowX) ? dx : 0, my = userScrollable(c.style.overflowY) ? dy : 0;
            if (c.element.canScrollBy(mx, my)) {
                c.element.scrollBy(mx, my, ScrollBehavior.AUTO);
                return true;
            }
        }
        return false;
    }

    /** Forgets state about a subtree leaving the document. */
    void forget(Node removed) {
        if (hovered != null && removed.contains(hovered)) hovered = null;
    }

    // ---- Scrollbars ----

    /** Updates which scrollbar is under the pointer (the painter widens it). Returns true if there is one. */
    boolean hover(HitResult hit) {
        HitResult.Scrollbar bar = hit == null ? null : hit.scrollbar();
        hovered = bar == null ? null : bar.container().element;
        hoveredVertical = bar != null && bar.vertical();
        return bar != null;
    }

    boolean isHovered(Element container, boolean vertical) {
        return hovered == container && hoveredVertical == vertical;
    }

    /**
     * Mousedown on a scrollbar ({@code hit.scrollbar()} is set): returns a drag that moves the thumb, or pages
     * toward the pointer when the track was hit (holding the capture until release).
     */
    Drag press(HitResult hit) {
        hover(hit); // stays highlighted while dragged
        Element e = hovered;
        boolean v = hoveredVertical;
        float[] thumb = Scrollbars.thumb(hit.box(), v, true);
        if (Dom.inside(thumb, hit.localX(), hit.localY())) {
            // Thumb movement along the axis, in the container's own (untransformed) space.
            float startPointer = v ? hit.localY() : hit.localX(), startScroll = v ? e.scrollTop() : e.scrollLeft();
            return (px, py) -> {
                if (e.box == null) return;
                float[] local = Dom.local(e.box, px, py);
                float s = startScroll + ((v ? local[1] : local[0]) - startPointer) * Scrollbars.scrollPerThumbPixel(e.box, v);
                e.scrollTo(v ? e.scrollLeft() : s, v ? s : e.scrollTop());
            };
        }
        boolean before = thumb != null && (v ? hit.localY() < thumb[1] : hit.localX() < thumb[0]);
        float page = PAGE * (v ? e.clientHeight() : e.clientWidth()) * (before ? -1 : 1);
        e.scrollBy(v ? 0 : page, v ? page : 0, ScrollBehavior.AUTO);
        return Drag.HOLD;
    }

    private static boolean userScrollable(Overflow o) {
        return o == Overflow.SCROLL || o == Overflow.AUTO;
    }
}
