package dev.vellum.engine.dom;

import dev.vellum.engine.event.Event;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Scrolling over time, for the whole document: eases the elements with a smooth scroll in progress toward their
 * destinations, and fires {@code scroll} once per frame at each element whose position changed since the last frame,
 * whatever moved it (input, scripts, smooth scrolling, or a relayout clamping it to a smaller range). Positions and
 * destinations live on the elements ({@link Element#scrollTo}); the input handler ticks this once per frame.
 */
public final class Scrolling {
    /** Time constant of the smooth-scroll easing; motion settles within about three of these (~120 ms). */
    private static final double SMOOTH_TAU_MS = 40;

    private final Set<Element> smooth = new LinkedHashSet<>(), moved = new LinkedHashSet<>();
    private final List<Element> scratch = new ArrayList<>();
    private double lastTick = Double.NaN;

    Scrolling() {}

    void animate(Element e) { smooth.add(e); }

    void stop(Element e) { smooth.remove(e); }

    void moved(Element e) { moved.add(e); }

    /** Whether the next {@link #tick} has work: a smooth scroll under way, or scroll events to fire. */
    public boolean isActive() {
        return !smooth.isEmpty() || !moved.isEmpty();
    }

    /** Forgets elements of a subtree leaving the document. */
    void forget(Node removed) {
        smooth.removeIf(removed::contains);
        moved.removeIf(removed::contains);
    }

    /**
     * Advances smooth scrolls to {@code now} (ms), then fires {@code scroll} at every element that moved since the
     * last tick. Returns whether any did, so what is under the pointer can be found again.
     */
    public boolean tick(double now) {
        double dt = Double.isNaN(lastTick) ? 16 : Math.max(0, now - lastTick);
        lastTick = now;
        if (!smooth.isEmpty()) {
            float k = (float) (1 - Math.exp(-dt / SMOOTH_TAU_MS));
            smooth.removeIf(e -> e.stepSmoothScroll(k));
        }
        if (moved.isEmpty()) return false;
        // Listeners may scroll again: those moves fire next frame.
        scratch.addAll(moved);
        moved.clear();
        try {
            for (Element e : scratch) e.dispatchEvent(new Event("scroll", false, false));
        } finally {
            scratch.clear();
        }
        return true;
    }
}
