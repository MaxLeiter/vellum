package dev.vellum.engine.input;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.layout.Box;
import dev.vellum.engine.paint.Scrollbars;
import dev.vellum.engine.style.Overflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User scrolling of scroll containers: the wheel with scroll chaining, smooth scrolling (eased toward a per-element
 * destination in {@link #tick}), overlay scrollbars (hover, thumb drag, track paging) and scrolling an element into
 * view.
 */
final class Scroller {
    /** Time constant of the smooth-scroll easing; motion settles within about three of these (~120 ms). */
    private static final double SMOOTH_TAU_MS = 40;
    /** Fraction of the visible size that a click on the scrollbar track pages by. */
    private static final float PAGE = 0.875f;

    private record Bar(Element element, boolean vertical) {}

    private final Document document;
    /** Smooth-scroll destinations {left, top}. */
    private final Map<Element, float[]> targets = new LinkedHashMap<>();
    private double lastTick = Double.NaN;
    private Bar hovered;

    Scroller(Document document) {
        this.document = document;
    }

    // ---- Wheel and programmatic scrolling ----

    /**
     * Scrolls the nearest container from {@code start} outward that can still move by (dx, dy) along an axis users
     * may scroll ({@code overflow: scroll | auto}). Returns true if one did.
     */
    boolean wheel(Element start, float dx, float dy) {
        for (Element e = start; e != null; e = e.parentElement()) {
            if (e.box == null || !e.box.isScrollContainer()) continue;
            float[] to = destination(e);
            float mx = userScrollable(e.box.style.overflowX) ? dx : 0;
            float my = userScrollable(e.box.style.overflowY) ? dy : 0;
            if (canMove(to[0], mx, maxLeft(e)) || canMove(to[1], my, maxTop(e))) {
                scrollBy(e, mx, my);
                return true;
            }
        }
        return false;
    }

    /** Scrolls by a delta: eased when the element has {@code scroll-behavior: smooth} and motion is allowed. */
    void scrollBy(Element e, float dx, float dy) {
        float[] to = destination(e);
        scrollTo(e, to[0] + dx, to[1] + dy, Forms.style(e).scrollSmooth && !document.host().prefersReducedMotion());
    }

    private void scrollTo(Element e, float left, float top, boolean smooth) {
        float l = clamp(left, maxLeft(e)), t = clamp(top, maxTop(e));
        if (smooth) {
            targets.put(e, new float[] {l, t});
        } else {
            targets.remove(e);
            e.scrollTo(l, t);
        }
    }

    /** Where the element is heading: its smooth-scroll destination, else its current offset. */
    private float[] destination(Element e) {
        float[] t = targets.get(e);
        return t != null ? t : new float[] {e.scrollLeft, e.scrollTop};
    }

    /** Advances smooth scrolls with exponential easing. Returns true if anything moved. */
    boolean tick(double now) {
        double dt = Double.isNaN(lastTick) ? 16 : Math.max(0, now - lastTick);
        lastTick = now;
        if (targets.isEmpty()) return false;
        float k = (float) (1 - Math.exp(-dt / SMOOTH_TAU_MS));
        for (Element e : List.copyOf(targets.keySet())) {
            float[] t = targets.get(e);
            if (t == null) continue; // removed by a scroll listener
            float left = approach(e.scrollLeft, t[0], k), top = approach(e.scrollTop, t[1], k);
            if (left == t[0] && top == t[1]) targets.remove(e);
            e.scrollTo(left, top);
        }
        return true;
    }

    /**
     * Scrolls every scroll container around {@code e} the least amount that brings it into view (instantly, as focus
     * navigation does).
     */
    void scrollIntoView(Element e) {
        Box box = Dom.boxOf(e);
        if (box == null) return;
        for (Box c = box.parent; c != null; c = c.parent) {
            if (!c.isScrollContainer()) continue;
            float dx = overflow(box.absoluteX() - c.absoluteX() - c.borderLeft, box.width, c.paddingBoxWidth());
            float dy = overflow(box.absoluteY() - c.absoluteY() - c.borderTop, box.height, c.paddingBoxHeight());
            if (dx != 0 || dy != 0) scrollTo(c.element, c.element.scrollLeft + dx, c.element.scrollTop + dy, false);
        }
    }

    /** After relayout: clamps offsets and destinations of containers whose content shrank. */
    void clampAll() {
        // A snapshot: clamping fires scroll events, whose listeners may change the tree.
        for (Element e : document.descendants(e -> e.box != null && e.box.isScrollContainer())) {
            e.scrollTo(e.scrollLeft, e.scrollTop);
        }
        targets.replaceAll((e, t) -> new float[] {clamp(t[0], maxLeft(e)), clamp(t[1], maxTop(e))});
    }

    /** Forgets state about a subtree leaving the document. */
    void forget(Node removed) {
        targets.keySet().removeIf(removed::contains);
        if (hovered != null && removed.contains(hovered.element)) hovered = null;
    }

    // ---- Scrollbars ----

    /** Updates which scrollbar is under the pointer (the painter widens it). Returns true if there is one. */
    boolean hover(Element hit, float x, float y) {
        hovered = barAt(hit, x, y);
        return hovered != null;
    }

    boolean isHovered(Element container, boolean vertical) {
        return hovered != null && hovered.element == container && hovered.vertical == vertical;
    }

    /**
     * Mousedown on a scrollbar: returns a drag that moves the thumb, or pages toward the pointer when the track was
     * hit (holding the capture until release). Null when the point is not on a scrollbar.
     */
    Drag press(Element hit, float x, float y) {
        Bar bar = barAt(hit, x, y);
        if (bar == null) return null;
        hovered = bar; // stays highlighted while dragged
        Element e = bar.element;
        boolean v = bar.vertical;
        Box c = e.box;
        float lx = x - c.absoluteX(), ly = y - c.absoluteY();
        float[] thumb = Scrollbars.thumb(c, v, true);
        if (Dom.inside(thumb, lx, ly)) {
            float startPointer = v ? y : x, startScroll = v ? e.scrollTop : e.scrollLeft;
            return (px, py) -> {
                if (e.box == null) return;
                float s = startScroll + ((v ? py : px) - startPointer) * Scrollbars.scrollPerThumbPixel(e.box, v);
                scrollTo(e, v ? e.scrollLeft : s, v ? s : e.scrollTop, false);
            };
        }
        boolean before = thumb != null && (v ? ly < thumb[1] : lx < thumb[0]);
        float page = PAGE * (v ? e.clientHeight() : e.clientWidth()) * (before ? -1 : 1);
        scrollBy(e, v ? 0 : page, v ? page : 0);
        return Drag.HOLD;
    }

    /** The scrollbar under a viewport point: one of the hit element's or of a scroll container around it. */
    private Bar barAt(Element hit, float x, float y) {
        for (Box c = hit == null ? null : Dom.boxOf(hit); c != null; c = c.parent) {
            if (!c.isScrollContainer()) continue;
            for (boolean vertical : new boolean[] {true, false}) {
                float[] track = Scrollbars.track(c, vertical, isHovered(c.element, vertical));
                if (Dom.inside(track, x - c.absoluteX(), y - c.absoluteY())) return new Bar(c.element, vertical);
            }
        }
        return null;
    }

    // ---- Helpers ----

    private static boolean userScrollable(Overflow o) {
        return o == Overflow.SCROLL || o == Overflow.AUTO;
    }

    /** True when a scroll position can still move by {@code delta} within [0, max]. */
    private static boolean canMove(float position, float delta, float max) {
        return delta > 0 ? position < max : delta < 0 && position > 0;
    }

    private static float maxLeft(Element e) {
        return Math.max(0, e.scrollWidth() - e.clientWidth());
    }

    private static float maxTop(Element e) {
        return Math.max(0, e.scrollHeight() - e.clientHeight());
    }

    private static float clamp(float v, float max) {
        return Math.max(0, Math.min(max, v));
    }

    private static float approach(float from, float to, float k) {
        float v = from + (to - from) * k;
        return Math.abs(to - v) < 0.5f ? to : v;
    }

    /** How far to scroll so [start, start + size) lies within [0, view); aligns the start when it does not fit. */
    private static float overflow(float start, float size, float view) {
        if (start < 0) return start;
        float end = start + size;
        return end > view ? Math.min(end - view, start) : 0;
    }
}
